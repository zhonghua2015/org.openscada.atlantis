package org.openscada.ae.event;

import java.util.LinkedList;
import java.util.Queue;

import org.openscada.ae.Event;
import org.openscada.ae.server.storage.Storage;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.Filter;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;

public class EventProcessor implements ServiceTrackerCustomizer
{
    private final ServiceTracker tracker;

    private final Filter filter;

    private final Queue<Event> eventQueue = new LinkedList<Event> ();

    private final BundleContext context;

    private Storage service;

    public EventProcessor ( final BundleContext context ) throws InvalidSyntaxException
    {
        this ( "(" + Constants.OBJECTCLASS + "=" + Storage.class.getName () + ")", context );
    }

    public EventProcessor ( final Filter filter, final BundleContext context )
    {
        this.filter = filter;
        this.context = context;
        this.tracker = new ServiceTracker ( context, this.filter, this );
    }

    public EventProcessor ( final String filter, final BundleContext context ) throws InvalidSyntaxException
    {
        this ( FrameworkUtil.createFilter ( filter ), context );
    }

    public void open ()
    {
        this.tracker.open ( true );
    }

    public void close ()
    {
        this.tracker.close ();
    }

    public synchronized void publishEvent ( final Event event )
    {
        final Storage service = this.service;
        if ( service != null )
        {
            service.store ( event );
        }
        else
        {
            this.eventQueue.add ( event );
        }
    }

    public synchronized Object addingService ( final ServiceReference reference )
    {
        final Object o = this.context.getService ( reference );
        if ( o instanceof Storage )
        {
            final Storage service = (Storage)o;
            publishStoredEvents ( service );
            this.service = service;
            return this.service;
        }
        return null;
    }

    private void publishStoredEvents ( final Storage service )
    {
        Event event = null;
        while ( ( event = this.eventQueue.poll () ) != null )
        {
            service.store ( event );
        }
    }

    public void modifiedService ( final ServiceReference reference, final Object service )
    {
    }

    public synchronized void removedService ( final ServiceReference reference, final Object service )
    {
        this.service = null;
    }
}
