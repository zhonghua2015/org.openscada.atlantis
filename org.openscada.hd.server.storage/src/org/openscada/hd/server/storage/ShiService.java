package org.openscada.hd.server.storage;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.openscada.ca.Configuration;
import org.openscada.core.Variant;
import org.openscada.da.client.DataItemValue;
import org.openscada.hd.HistoricalItemInformation;
import org.openscada.hd.Query;
import org.openscada.hd.QueryListener;
import org.openscada.hd.QueryParameters;
import org.openscada.hd.server.common.StorageHistoricalItem;
import org.openscada.hd.server.storage.internal.ConfigurationImpl;
import org.openscada.hd.server.storage.internal.Conversions;
import org.openscada.hd.server.storage.internal.QueryImpl;
import org.openscada.hsdb.CalculatingStorageChannel;
import org.openscada.hsdb.ExtendedStorageChannel;
import org.openscada.hsdb.StorageChannelMetaData;
import org.openscada.hsdb.calculation.CalculationLogicProvider;
import org.openscada.hsdb.calculation.CalculationMethod;
import org.openscada.hsdb.concurrent.HsdbThreadFactory;
import org.openscada.hsdb.datatypes.BaseValue;
import org.openscada.hsdb.datatypes.DataType;
import org.openscada.hsdb.datatypes.DoubleValue;
import org.openscada.hsdb.datatypes.LongValue;
import org.openscada.hsdb.relict.RelictCleaner;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of StorageHistoricalItem as OSGi service.
 * @see org.openscada.hd.server.common.StorageHistoricalItem
 * @author Ludwig Straub
 */
public class ShiService implements StorageHistoricalItem, RelictCleaner
{
    /** The default logger. */
    private final static Logger logger = LoggerFactory.getLogger ( ShiService.class );

    /** Id prefix of the startup thread. */
    private final static String STARTUP_THREAD_ID_PREFIX = "ShiStartup_";

    /** Id prefix of the data receiver thread. */
    private final static String DATA_RECEIVER_THREAD_ID_PREFIX = "ShiDataReceiver_";

    /** Configuration of the service. */
    private final Configuration configuration;

    /** Set of all calculation methods except NATIVE. */
    private final Set<CalculationMethod> calculationMethods;

    /** All available storage channels mapped via calculation method. */
    private final Map<StorageChannelMetaData, CalculatingStorageChannel> storageChannels;

    /** Reference to the main input storage channel that is also available in the storage channels map. */
    private CalculatingStorageChannel rootStorageChannel;

    /** Flag indicating whether the service is currently starting or not. */
    private boolean starting;

    /** Flag indicating whether the service is currently running or not. */
    private boolean started;

    /** List of currently open queries. */
    private final Collection<QueryImpl> openQueries;

    /** Expected input data type. */
    private DataType expectedDataType;

    /** Latest time of known valid information. */
    private final long latestReliableTime;

    /** Flag indicating whether old data should be deleted or not. */
    private final boolean importMode;

    /** Maximum age of data that will be processed by the service if not running in import mode. */
    private final long proposedDataAge;

    /** Maximum time in milliseconds in the future a new value is accepted and processed. */
    private final long acceptedFutureTime;

    /** Registration of this service. */
    private ServiceRegistration registration;

    /** Task that receives the incoming data. */
    private ExecutorService dataReceiver;

    /** Task that performs the startup of the service. */
    private ExecutorService startUpTask;

    /** Task that performs the startup operation for a new query. */
    private ExecutorService createQueryTask;

    /** Latest processed value. */
    private BaseValue latestProcessedValue;

    /** Object that is used for internal synchronization. */
    private final Object lockObject;

    /**
     * Constructor.
     * @param configuration configuration of the service
     * @param latestReliableTime latest time of known valid information
     * @param importMode flag indicating whether old data should be deleted or not
     */
    public ShiService ( final Configuration configuration, final long latestReliableTime, final boolean importMode )
    {
        this.configuration = new ConfigurationImpl ( configuration );
        calculationMethods = new HashSet<CalculationMethod> ( Conversions.getCalculationMethods ( configuration ) );
        this.storageChannels = new HashMap<StorageChannelMetaData, CalculatingStorageChannel> ();
        this.rootStorageChannel = null;
        this.lockObject = new Object ();
        this.starting = false;
        this.started = false;
        this.openQueries = new LinkedList<QueryImpl> ();
        expectedDataType = DataType.UNKNOWN;
        this.latestReliableTime = latestReliableTime;
        this.importMode = importMode;
        final Map<String, String> data = configuration.getData ();
        this.proposedDataAge = Conversions.decodeTimeSpan ( data.get ( Conversions.PROPOSED_DATA_AGE_KEY_PREFIX + 0 ) );
        this.acceptedFutureTime = Conversions.decodeTimeSpan ( data.get ( Conversions.ACCEPTED_FUTURE_TIME_KEY_PREFIX ) );
        registration = null;
    }

    /**
     * This method adds a query to the collection of currently open queries.
     * @param query query to be added
     */
    public synchronized void addQuery ( final QueryImpl query )
    {
        openQueries.add ( query );
    }

    /**
     * This method removes a query from the collection of currently open queries.
     * If the query is not found in the collection then no action is performed.
     * @param query query to be removed
     */
    public synchronized void removeQuery ( final QueryImpl query )
    {
        openQueries.remove ( query );
    }

    /**
     * This method returns the maximum available compression level of all storage channels.
     * @return maximum available compression level of all storage channels or negative value if no storage channel is availavle
     */
    public synchronized long getMaximumCompressionLevel ()
    {
        long maximumAvailableCompressionLevel = Long.MIN_VALUE;
        for ( final StorageChannelMetaData metaData : storageChannels.keySet () )
        {
            maximumAvailableCompressionLevel = Math.max ( maximumAvailableCompressionLevel, metaData.getDetailLevelId () );
        }
        return maximumAvailableCompressionLevel;
    }

    /**
     * This method returns the lowest compression level that is sufficient for the specified detail level
     * @param timespan time span in milliseconds the requested compression level must provide at least in order to be considered as recommended compression level
     * @return recommended compression level
     */
    public synchronized long getRecommendedCompressionLevel ( final long timespan )
    {
        // optimize in case of minimal time span
        if ( timespan <= 1 )
        {
            return 0;
        }

        // search for optimal compression level
        long minimumAvailableCompressionLevel = 0;
        long providedTimespan = Long.MIN_VALUE;
        for ( final StorageChannelMetaData metaData : storageChannels.keySet () )
        {
            if ( metaData.getDetailLevelId () > 0 )
            {
                final long compressionTimespan = metaData.getCalculationMethodParameters ()[0];
                if ( ( compressionTimespan < timespan ) && ( compressionTimespan > providedTimespan ) )
                {
                    providedTimespan = compressionTimespan;
                    minimumAvailableCompressionLevel = metaData.getDetailLevelId ();
                }
            }
        }
        return minimumAvailableCompressionLevel;
    }

    /**
     * This method returns the latest NATIVE value or null, if no value is available at all.
     * @return latest NATIVE value or null, if no value is available at all
     */
    public synchronized BaseValue getLatestValue ()
    {
        if ( rootStorageChannel != null )
        {
            try
            {
                final BaseValue[] result = rootStorageChannel.getMetaData ().getDataType () == DataType.LONG_VALUE ? rootStorageChannel.getLongValues ( Long.MAX_VALUE - 1, Long.MAX_VALUE ) : rootStorageChannel.getDoubleValues ( Long.MAX_VALUE - 1, Long.MAX_VALUE );
                return ( result != null ) && ( result.length > 0 ) ? result[0] : null;
            }
            catch ( final Exception e )
            {
                logger.error ( "unable to retriebe latest value from root storage channel", e );
            }
        }
        return null;
    }

    /**
     * This method returns the currently available values for the given time span.
     * The returned map contains all available storage channels for the given level.
     * @param compressionLevel compression level for which data has to be retrieved
     * @param startTime start time of the requested data
     * @param endTime end time of the requested data
     * @return map containing all available storage channels for the given level
     * @throws Exception in case of problems retrieving the requested data
     */
    public synchronized Map<StorageChannelMetaData, BaseValue[]> getValues ( final long compressionLevel, final long startTime, final long endTime ) throws Exception
    {
        logger.debug ( "requested compression level: " + compressionLevel );
        final Map<StorageChannelMetaData, BaseValue[]> result = new HashMap<StorageChannelMetaData, BaseValue[]> ();
        try
        {
            for ( final Entry<StorageChannelMetaData, CalculatingStorageChannel> entry : storageChannels.entrySet () )
            {
                final StorageChannelMetaData metaData = entry.getKey ();
                if ( metaData.getDetailLevelId () == compressionLevel )
                {
                    final CalculatingStorageChannel storageChannel = entry.getValue ();
                    BaseValue[] values = null;
                    switch ( storageChannel.getCalculationLogicProvider ().getOutputType () )
                    {
                    case LONG_VALUE:
                    {
                        values = storageChannel.getLongValues ( startTime, endTime );
                        break;
                    }
                    case DOUBLE_VALUE:
                    {
                        values = storageChannel.getDoubleValues ( startTime, endTime );
                        break;
                    }
                    }
                    if ( compressionLevel == 0 )
                    {
                        // create a virtual entry for each required calculation method
                        for ( final CalculationMethod calculationMethod : calculationMethods )
                        {
                            final StorageChannelMetaData subMetaData = new StorageChannelMetaData ( metaData );
                            subMetaData.setCalculationMethod ( calculationMethod );
                            result.put ( subMetaData, values );
                        }
                    }
                    else
                    {
                        result.put ( storageChannel.getMetaData (), values );
                    }
                }
            }
        }
        catch ( final Exception e )
        {
            final String message = "unable to retrieve values from storage channel";
            logger.error ( message, e );
            throw new Exception ( message, e );
        }
        return result;
    }

    /**
     * This method returns a reference to the current configuration of the service.
     * @return reference to the current configuration of the service
     */
    public synchronized Configuration getConfiguration ()
    {
        return new ConfigurationImpl ( configuration );
    }

    /**
     * @see org.openscada.hd.server.common.StorageHistoricalItem#createQuery
     */
    public Query createQuery ( final QueryParameters parameters, final QueryListener listener, final boolean updateData )
    {
        try
        {
            final QueryImpl query = new QueryImpl ( this, listener, parameters, calculationMethods, updateData );
            final ShiService service = this;
            createQueryTask.submit ( new Runnable () {
                public void run ()
                {
                    synchronized ( this )
                    {
                        service.addQuery ( query );
                    }
                }
            } );
            return query;
        }
        catch ( final Exception e )
        {
            logger.warn ( "Failed to create query", e );
        }
        return null;
    }

    /**
     * @see org.openscada.hd.server.common.StorageHistoricalItem#getInformation
     */
    public synchronized HistoricalItemInformation getInformation ()
    {
        return Conversions.convertConfigurationToHistoricalItemInformation ( configuration );
    }

    /**
     * @see org.openscada.hd.server.common.StorageHistoricalItem#updateData
     */
    public void updateData ( final DataItemValue value )
    {
        final long now = System.currentTimeMillis ();
        logger.debug ( "receiving data at: " + now );
        if ( dataReceiver != null )
        {
            dataReceiver.submit ( new Runnable () {
                public void run ()
                {
                    // check if input is valid
                    logger.debug ( "processing data after: " + ( System.currentTimeMillis () - now ) );
                    if ( value == null )
                    {
                        createInvalidEntry ( now, rootStorageChannel );
                        return;
                    }
                    final Variant variant = value.getValue ();
                    if ( variant == null )
                    {
                        createInvalidEntry ( now, rootStorageChannel );
                        return;
                    }
                    final Calendar calendar = value.getTimestamp ();
                    final long time = calendar == null ? now : calendar.getTimeInMillis ();
                    if ( !importMode && ( ( now - proposedDataAge ) > time ) )
                    {
                        logger.warn ( String.format ( "data that is too old for being processed was received! data will be ignored: (configuration: '%s'; time: %s)", configuration.getId (), time ) );
                        return;
                    }
                    if ( ( now + acceptedFutureTime ) < time )
                    {
                        logger.warn ( String.format ( "timestamp of data is located too far in the future! data will be ignored: (configuration: '%s'; time: %s)", configuration.getId (), time ) );
                        return;
                    }

                    // process data
                    final double qualityIndicator = !value.isConnected () || value.isError () ? 0 : 1;
                    final double isManual = value.isManual () ? 1 : 0;
                    if ( expectedDataType == DataType.LONG_VALUE )
                    {
                        processData ( new LongValue ( time, qualityIndicator, isManual, 1, variant.asLong ( 0L ) ) );
                    }
                    else
                    {
                        processData ( new DoubleValue ( time, qualityIndicator, isManual, 1, variant.asDouble ( 0.0 ) ) );
                    }
                    logger.debug ( "data processing time: " + ( System.currentTimeMillis () - now ) );

                    // check processed data type and give warning if type does not match the expected type
                    final DataType receivedDataType = variant.isBoolean () || variant.isInteger () || variant.isLong () ? DataType.LONG_VALUE : DataType.DOUBLE_VALUE;
                    if ( !variant.isNull () && ( expectedDataType != receivedDataType ) )
                    {
                        logger.warn ( String.format ( "received data type (%s) does not match expected data type (%s)!", receivedDataType, expectedDataType ) );
                    }
                }
            } );
        }
    }

    /**
     * This method processes the data tha is received via the data receiver task.
     * @param value data that has to be processed
     */
    public synchronized void processData ( final BaseValue value )
    {
        if ( !this.started || ( this.rootStorageChannel == null ) || ( value == null ) )
        {
            return;
        }
        if ( latestProcessedValue == null )
        {
            latestProcessedValue = getLatestValue ();
        }
        if ( latestProcessedValue == null )
        {
            latestProcessedValue = value;
        }
        if ( value.getTime () < latestProcessedValue.getTime () )
        {
            logger.warn ( String.format ( "older value for configuration '%s' received than latest available value", configuration.getId () ) );
        }
        try
        {
            // process data
            if ( value instanceof LongValue )
            {
                final LongValue longValue = (LongValue)value;
                this.rootStorageChannel.updateLong ( longValue );
                for ( final QueryImpl query : this.openQueries )
                {
                    query.updateLong ( longValue );
                }
            }
            else
            {
                final DoubleValue doubleValue = (DoubleValue)value;
                this.rootStorageChannel.updateDouble ( doubleValue );
                for ( final QueryImpl query : this.openQueries )
                {
                    query.updateDouble ( doubleValue );
                }
            }

            // when importing data, the values are most likely strongly differing from each other in time.
            // this causes additional files to be generated that can be cleaned to increase performance
            if ( importMode )
            {
                cleanupRelicts ();
            }
        }
        catch ( final Exception e )
        {
            logger.error ( "could not process value", e );
        }
    }

    /**
     * This method adds a storage channel.
     * @param storageChannel storage channel that has to be added
     */
    public synchronized void addStorageChannel ( final CalculatingStorageChannel storageChannel )
    {
        if ( storageChannel != null )
        {
            try
            {
                final StorageChannelMetaData metaData = storageChannel.getMetaData ();
                if ( metaData != null )
                {
                    storageChannels.put ( metaData, storageChannel );
                    if ( metaData.getCalculationMethod () == CalculationMethod.NATIVE )
                    {
                        rootStorageChannel = storageChannel;
                        expectedDataType = metaData.getDataType ();
                    }
                }
            }
            catch ( final Exception e )
            {
                logger.error ( "could not retrieve meta data information of storage channel", e );
            }
        }
    }

    /**
     * This method creates an invalid marker entry using the data of the latest existing entry.
     * No entry is made if no data at all is available in the root storage channel.
     * It is assured that the time of the new entry is after the latest existing entry.
     * If necessary, the passed time will be increased to fit this requirement.
     * @param time time of the entry that has to be created
     * @param storageChannel storage channel to which the invalid value should be written
     * @param dataType data type of the invalid marker entry.
     */
    private void createInvalidEntry ( final long time, final ExtendedStorageChannel storageChannel, final DataType dataType )
    {
        if ( storageChannel != null )
        {
            BaseValue[] values = null;
            try
            {
                if ( dataType == DataType.LONG_VALUE )
                {
                    values = storageChannel.getLongValues ( Long.MAX_VALUE - 1, Long.MAX_VALUE );
                }
                else
                {
                    values = storageChannel.getDoubleValues ( Long.MAX_VALUE - 1, Long.MAX_VALUE );
                }
            }
            catch ( final Exception e )
            {
                logger.error ( "could not retrieve latest value from root storage channel", e );
            }
            if ( ( values != null ) && ( values.length > 0 ) )
            {
                final BaseValue value = values[values.length - 1];
                try
                {
                    if ( value instanceof LongValue )
                    {
                        storageChannel.updateLong ( new LongValue ( Math.max ( value.getTime () + 1, time ), 0, 0, 0, ( (LongValue)value ).getValue () ) );
                    }
                    else
                    {
                        storageChannel.updateDouble ( new DoubleValue ( Math.max ( value.getTime () + 1, time ), 0, 0, 0, ( (DoubleValue)value ).getValue () ) );
                    }
                }
                catch ( final Exception e )
                {
                    logger.error ( "could not write value with quality=0", e );
                }
            }
        }
    }

    /**
     * This method creates an invalid marker entry using the data of the latest existing entry.
     * No entry is made if no data at all is available in the root storage channel.
     * It is assured that the time of the new entry is after the latest existing entry.
     * If necessary, the passed time will be increased to fit this requirement.
     * @param time time of the entry that has to be created
     * @param storageChannel storage channel to which the invalid value should be written
     */
    private void createInvalidEntry ( final long time, final CalculatingStorageChannel storageChannel )
    {
        createInvalidEntry ( time, storageChannel, storageChannel.getCalculationLogicProvider ().getOutputType () );
    }

    /**
     * This method activates the service processing and registers the service as OSGi service.
     * The methods updateData and createQuery only have effect after calling this method.
     * @param bundleContext OSGi bundle context
     */
    public synchronized void start ( final BundleContext bundleContext )
    {
        stop ();
        starting = true;
        started = true;
        final String configurationId = configuration.getId ();
        dataReceiver = Executors.newSingleThreadExecutor ( HsdbThreadFactory.createFactory ( DATA_RECEIVER_THREAD_ID_PREFIX + configurationId ) );
        startUpTask = Executors.newSingleThreadExecutor ( HsdbThreadFactory.createFactory ( STARTUP_THREAD_ID_PREFIX + configurationId ) );
        createQueryTask = Executors.newSingleThreadExecutor ( HsdbThreadFactory.createFactory ( STARTUP_THREAD_ID_PREFIX + configurationId ) );
        startUpTask.submit ( new Runnable () {
            public void run ()
            {
                startUpTask.shutdown ();
                if ( !postprocessData () )
                {
                    stop ();
                    return;
                }
                boolean proceed = true;
                synchronized ( lockObject )
                {
                    proceed = starting;
                }
                if ( !proceed )
                {
                    stop ();
                    return;
                }
                if ( !importMode )
                {
                    createInvalidEntry ( latestReliableTime, rootStorageChannel );
                }
                registerService ( bundleContext );
                synchronized ( lockObject )
                {
                    proceed = starting;
                    starting = false;
                }
                if ( !proceed )
                {
                    stop ();
                    return;
                }
            }
        } );
    }

    /**
     * This method registers the service via OSGi.
     * @param bundleContext OSGi bundle context
     */
    private synchronized void registerService ( final BundleContext bundleContext )
    {
        unregisterService ();
        final Dictionary<String, String> serviceProperties = new Hashtable<String, String> ();
        serviceProperties.put ( Constants.SERVICE_PID, configuration.getId () );
        serviceProperties.put ( Constants.SERVICE_VENDOR, "inavare GmbH" );
        serviceProperties.put ( Constants.SERVICE_DESCRIPTION, "A OpenSCADA Storage Historical Item Implementation" );
        registration = bundleContext.registerService ( new String[] { ShiService.class.getName (), StorageHistoricalItem.class.getName () }, this, serviceProperties );
    }

    /**
     * This method unregisters a previously registered service.
     */
    private synchronized void unregisterService ()
    {
        if ( registration != null )
        {
            registration.unregister ();
            registration = null;
        }
    }

    /**
     * This method stops the service from processing and destroys its internal structure.
     * The service cannot be started again, after stop has been called.
     * After calling this method, no further call to this service can be made.
     * Before the service is stopped, an invalid value is processed in order to mark
     * future values as invalid until a valid value is processed again.
     */
    public synchronized void stop ()
    {
        // abort if service is not yet started
        if ( starting )
        {
            synchronized ( lockObject )
            {
                if ( starting )
                {
                    starting = false;
                    return;
                }
            }
        }

        // close existing queries
        for ( final QueryImpl query : new ArrayList<QueryImpl> ( openQueries ) )
        {
            query.close ();
        }

        // unregister service
        unregisterService ();

        // stop data receiver
        if ( dataReceiver != null )
        {
            dataReceiver.shutdown ();
            dataReceiver = null;
        }

        // stop startup task
        if ( startUpTask != null )
        {
            startUpTask.shutdown ();
            startUpTask = null;
        }

        // stop query startup task but keep it available to avoid synchronization issues
        if ( createQueryTask != null )
        {
            createQueryTask.shutdown ();
        }

        // create entry with data marked as invalid
        if ( started && !importMode )
        {
            createInvalidEntry ( System.currentTimeMillis (), rootStorageChannel );
        }

        // set running flag
        started = false;
    }

    /**
     * This method cleans old data.
     * @see org.openscada.hsdb.relict.RelictCleaner#cleanupRelicts
     */
    public synchronized void cleanupRelicts () throws Exception
    {
        if ( started && ( rootStorageChannel != null ) )
        {
            try
            {
                // clean data
                logger.info ( "cleaning old data" );
                rootStorageChannel.cleanupRelicts ();

                // notify queries
                for ( final QueryImpl query : openQueries )
                {
                    query.updateDataBefore ( System.currentTimeMillis () - proposedDataAge );
                }
            }
            catch ( final Exception e )
            {
                logger.error ( "could not clean old data", e );
            }
        }
    }

    /**
     * This method iterates over all storage channels and checks whether the latest entry of each storage channel matches the expected time stamp.
     * In the scenario that data has been deleted, the deleted data will be calculated again.
     */
    private boolean postprocessData ()
    {
        logger.info ( String.format ( "start calculating missing data for configuration '%s'", configuration.getId () ) );
        boolean abort = false;

        // process high (compression level) storage channels first, since their values will be influenced while calculating values of lower storage channels
        for ( long i = getMaximumCompressionLevel (); i >= 1; i-- )
        {
            for ( final Entry<StorageChannelMetaData, CalculatingStorageChannel> entry : storageChannels.entrySet () )
            {
                final StorageChannelMetaData metaData = entry.getKey ();
                final long compressionLevel = metaData.getDetailLevelId ();
                if ( compressionLevel != i )
                {
                    continue;
                }
                logger.debug ( "start calculating missing data for configuration '%s' and channel '%s'", configuration.getId (), metaData );
                try
                {
                    // get last values of the storage channel that has to be checked and possibly calculated again
                    final CalculationMethod requiredBaseCalculationMethod = compressionLevel == 1 ? CalculationMethod.NATIVE : metaData.getCalculationMethod ();
                    final CalculatingStorageChannel storageChannel = entry.getValue ();
                    final CalculationLogicProvider calculationLogicProvider = storageChannel.getCalculationLogicProvider ();
                    final long timespan = calculationLogicProvider.getRequiredTimespanForCalculation ();
                    BaseValue[] lastValues;
                    final DataType dataType = calculationLogicProvider.getOutputType ();
                    if ( dataType == DataType.LONG_VALUE )
                    {
                        lastValues = storageChannel.getLongValues ( latestReliableTime - timespan - 1, latestReliableTime - timespan );
                    }
                    else
                    {
                        lastValues = storageChannel.getDoubleValues ( latestReliableTime - timespan - 1, latestReliableTime - timespan );
                    }
                    if ( ( lastValues == null ) || ( lastValues.length == 0 ) )
                    {
                        break;
                    }

                    // get the required base storage channel and process the missing values
                    final CalculatingStorageChannel subStorageChannel = (CalculatingStorageChannel)storageChannel.getInputStorageChannel ();
                    final StorageChannelMetaData subMetaData = subStorageChannel.getMetaData ();
                    if ( ( subMetaData.getDetailLevelId () == compressionLevel - 1 ) && ( subMetaData.getCalculationMethod () == requiredBaseCalculationMethod ) )
                    {
                        final BaseValue[] lastBaseValues;
                        final long endTime = latestReliableTime == Long.MIN_VALUE ? Long.MAX_VALUE : latestReliableTime;
                        final DataType baseDataType = subStorageChannel.getCalculationLogicProvider ().getOutputType ();
                        if ( baseDataType == DataType.LONG_VALUE )
                        {
                            lastBaseValues = subStorageChannel.getLongValues ( endTime - 1, endTime );
                        }
                        else
                        {
                            lastBaseValues = subStorageChannel.getDoubleValues ( endTime - 1, endTime );
                        }
                        if ( ( lastBaseValues == null ) || ( lastBaseValues.length == 0 ) )
                        {
                            break;
                        }
                        final long lastAvailableTime = lastBaseValues[lastBaseValues.length - 1].getTime ();
                        final long lastAlreadyCalculatedTime = lastValues[lastValues.length - 1].getTime ();
                        long lastTime = lastAlreadyCalculatedTime;
                        boolean first = true;
                        while ( !abort && ( lastTime < lastAvailableTime ) )
                        {
                            // mark time as invalid for which no base value is available to calculate value again
                            if ( first )
                            {
                                BaseValue[] oldBaseValues;
                                if ( baseDataType == DataType.LONG_VALUE )
                                {
                                    oldBaseValues = subStorageChannel.getLongValues ( lastAlreadyCalculatedTime - 1, lastAlreadyCalculatedTime );
                                }
                                else
                                {
                                    oldBaseValues = subStorageChannel.getDoubleValues ( lastAlreadyCalculatedTime - 1, lastAlreadyCalculatedTime );
                                }
                                if ( ( oldBaseValues != null ) && ( oldBaseValues.length > 0 ) )
                                {
                                    createInvalidEntry ( lastAlreadyCalculatedTime + 1, storageChannel.getBaseStorageChannel (), dataType );
                                }
                                first = false;
                            }

                            // since this loop can be time consuming, check whether the application has to be shut down again
                            if ( abort )
                            {
                                createInvalidEntry ( lastTime + 1, storageChannel );
                                logger.error ( String.format ( "aborting calculating missing data for configuration '%s'. marking not yet calculated time for '%s' as invalid", configuration.getId (), metaData ) );
                                continue;
                            }

                            // trigger calculation of data
                            storageChannel.notifyNewValues ( new long[] { lastTime } );

                            // check for abort criterion
                            synchronized ( lockObject )
                            {
                                abort &= starting;
                            }
                            lastTime += timespan;
                        }
                        break;
                    }
                }
                catch ( final Exception e )
                {
                    final String message = String.format ( "error while re-calculating data for storage channel (%s)", metaData );
                    logger.error ( message, e );
                }
                break;
            }
        }
        logger.info ( String.format ( "end calculating missing data for configuration '%s'", configuration.getId () ) );
        return true;
    }
}
