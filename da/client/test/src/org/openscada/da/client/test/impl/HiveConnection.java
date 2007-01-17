/*
 * This file is part of the OpenSCADA project
 * Copyright (C) 2006 inavare GmbH (http://inavare.com)
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package org.openscada.da.client.test.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Observable;

import org.apache.log4j.Logger;
import org.eclipse.ui.IActionFilter;
import org.eclipse.ui.views.properties.IPropertyDescriptor;
import org.eclipse.ui.views.properties.IPropertySource;
import org.eclipse.ui.views.properties.PropertyDescriptor;
import org.openscada.core.Variant;
import org.openscada.core.client.ConnectionFactory;
import org.openscada.core.client.ConnectionInformation;
import org.openscada.core.client.ConnectionState;
import org.openscada.core.client.ConnectionStateListener;
import org.openscada.da.client.Connection;
import org.openscada.da.client.FolderManager;
import org.openscada.da.client.ItemManager;
import org.openscada.da.client.test.Openscada_da_client_testPlugin;
import org.openscada.da.client.test.config.HiveConnectionInformation;

public class HiveConnection extends Observable implements IActionFilter, IPropertySource
{
    private static Logger _log = Logger.getLogger ( HiveConnection.class );
    
    private boolean _connectionRequested = false;
    private ConnectionInformation _connectionInformation = null;
    private Connection _connection = null;
    
    private Map < String, HiveItem > _itemMap = new HashMap < String, HiveItem > ();
    
    private FolderEntry _rootFolder = null;

    private ItemManager _itemManager;
    private FolderManager _folderManager;
    
    private enum Properties
    {
        URI,
        STATE
    };
    
    public HiveConnection ( HiveConnectionInformation connectionInfo )
    {
        _connectionInformation = ConnectionInformation.fromURI ( connectionInfo.getConnectionString () );
        
        try
        {
            Class.forName ( "org.openscada.da.client.net.Connection" );
        }
        catch ( ClassNotFoundException e )
        {}
        
        _connection = (Connection)ConnectionFactory.create ( _connectionInformation );
        
        _connection.addConnectionStateListener ( new ConnectionStateListener(){

            public void stateChange ( org.openscada.core.client.Connection connection, ConnectionState state, Throwable error )
            {
                performStateChange ( state, error );
            }

        });
        _itemManager = new ItemManager ( _connection );
        _folderManager = new FolderManager ( _connection );
    }
    
    public void connect ()
    {
        //if ( _connectionRequested )
        //    return;
        
        _connectionRequested = true;
        setChanged ();
        notifyObservers ();
        
        //if ( _connection != null )
        //    return;
        
        _log.debug ( "Initiating connection..." );
        
        try
        {
            _connection.connect ();
        }
        catch ( Exception e )
        {
            _log.error ( "Failed to start connection", e );
            Openscada_da_client_testPlugin.logError ( 1, "Unable to connect", e );
        }
        _log.debug ( "Connection fired up..." );
    }
    
    public void disconnect ()
    {
        _connectionRequested = false;
        
        setChanged ();
        notifyObservers ();
        
        _connection.disconnect ();
    }
    
    public ConnectionInformation getConnectionInformation()
    {
        return _connectionInformation;
    }
    
    private synchronized void performStateChange ( ConnectionState state, Throwable error )
    {
        _log.debug ( String.format ( "State Change to %s (%s)", state, error ) );
        
        switch ( state )
        {
        case BOUND:
            _rootFolder = new FolderEntry ( "", new HashMap<String, Variant>(), null, this, true );
            break;
        case CLOSED:
            if ( _rootFolder != null )
            {
                _rootFolder.dispose ();
                _rootFolder = null;
            }
            break;
        default:
            break;
        }
        
        setChanged ();
        notifyObservers ();
        
        if ( error != null )
        {
            Openscada_da_client_testPlugin.getDefault ().notifyError ( "Connection failed", error );
        }
    }
   
    public Connection getConnection ()
    {
        return _connection;
    }

    public boolean isConnectionRequested ()
    {
        return _connectionRequested;
    }
    
    synchronized public HiveItem lookupItem ( String itemName )
    {
        return _itemMap.get ( itemName );
    }

    public boolean testAttribute ( Object target, String name, String value )
    {
        if ( name.equals ( "state" ) )
        {
            return _connection.getState ().equals ( ConnectionState.valueOf ( value ) );
        }
        return false;
    }

    public FolderEntry getRootFolder ()
    {
        return _rootFolder;
    }
    
    public void notifyFolderChange ( FolderEntry folder )
    {
        setChanged ();
        notifyObservers ( folder );
    }
    
    protected void fillPropertyDescriptors ( List<IPropertyDescriptor> list )
    {
        {
            PropertyDescriptor pd = new PropertyDescriptor ( Properties.URI, "URI" );
            pd.setCategory ( "Connection Information" );
            pd.setAlwaysIncompatible ( true );
            list.add ( pd );
        }
        {
            PropertyDescriptor pd = new PropertyDescriptor ( Properties.STATE, "State" );
            pd.setCategory ( "Connection" );
            pd.setAlwaysIncompatible ( true );
            list.add ( pd );
        }
    }
    
    public IPropertyDescriptor[] getPropertyDescriptors ()
    {
        List<IPropertyDescriptor> list = new ArrayList<IPropertyDescriptor> ();
        
        fillPropertyDescriptors ( list );
        
        return list.toArray ( new IPropertyDescriptor[list.size()] );
    }
    
    public Object getPropertyValue ( Object id )
    {
        if ( id.equals ( Properties.URI ) )
            return _connectionInformation.toString ();
        if ( id.equals ( Properties.STATE  ) )
            return _connection.getState ().name ();
        
        return null;
    }
    
    public Object getEditableValue ()
    {
        return _connectionInformation.toString ();
    }
    
    public boolean isPropertySet ( Object id )
    {
        return false;
    }

    public void resetPropertyValue ( Object id )
    {
        // no op
    }

    public void setPropertyValue ( Object id, Object value )
    {
        // no op
    }
    
    public ItemManager getItemManager ()
    {
        return _itemManager;
    }
    
    public FolderManager getFolderManager ()
    {
        return _folderManager;
    }
    
}
