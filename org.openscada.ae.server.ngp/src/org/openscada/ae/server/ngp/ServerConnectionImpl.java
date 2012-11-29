/*
 * This file is part of the openSCADA project
 * Copyright (C) 2011-2012 TH4 SYSTEMS GmbH (http://th4-systems.com)
 *
 * openSCADA is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License version 3
 * only, as published by the Free Software Foundation.
 *
 * openSCADA is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License version 3 for more details
 * (a copy is included in the LICENSE file that accompanied this code).
 *
 * You should have received a copy of the GNU Lesser General Public License
 * version 3 along with openSCADA. If not, see
 * <http://opensource.org/licenses/lgpl-3.0.html> for a copy of the LGPLv3 License.
 */

package org.openscada.ae.server.ngp;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.mina.core.session.IoSession;
import org.openscada.ae.Event;
import org.openscada.ae.Query;
import org.openscada.ae.data.BrowserEntry;
import org.openscada.ae.data.EventInformation;
import org.openscada.ae.data.QueryState;
import org.openscada.ae.data.message.AcknowledgeRequest;
import org.openscada.ae.data.message.AcknowledgeResponse;
import org.openscada.ae.data.message.BrowseData;
import org.openscada.ae.data.message.CloseQuery;
import org.openscada.ae.data.message.CreateQuery;
import org.openscada.ae.data.message.LoadMore;
import org.openscada.ae.data.message.StartBrowse;
import org.openscada.ae.data.message.StopBrowse;
import org.openscada.ae.data.message.SubscribeMonitorPool;
import org.openscada.ae.data.message.UnsubscribeMonitorPool;
import org.openscada.ae.data.message.UpdateQueryData;
import org.openscada.ae.data.message.UpdateQueryState;
import org.openscada.ae.server.Service;
import org.openscada.ae.server.Session;
import org.openscada.core.InvalidSessionException;
import org.openscada.core.data.ErrorInformation;
import org.openscada.core.data.OperationParameters;
import org.openscada.core.data.Response;
import org.openscada.core.server.ngp.ServiceServerConnection;
import org.openscada.sec.PermissionDeniedException;
import org.openscada.utils.ExceptionHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerConnectionImpl extends ServiceServerConnection<Session, Service>
{
    private final static Logger logger = LoggerFactory.getLogger ( ServerConnectionImpl.class );

    private BrowserListenerManager browserListenerManager;

    private final Map<Long, QueryImpl> queries = new HashMap<Long, QueryImpl> ();

    public ServerConnectionImpl ( final IoSession session, final Service service )
    {
        super ( session, service );
    }

    @Override
    public void dispose ()
    {
        for ( final QueryImpl query : this.queries.values () )
        {
            query.close ();
        }
        this.queries.clear ();
        super.dispose ();
    }

    @Override
    public synchronized void messageReceived ( final Object message ) throws Exception
    {
        logger.trace ( "Received message: {}", message );

        if ( message instanceof StartBrowse )
        {
            handleStartBrowse ();
        }
        else if ( message instanceof StopBrowse )
        {
            handelStopBrowse ();
        }
        else if ( message instanceof SubscribeMonitorPool )
        {
            handleSubscribeMonitorPool ( (SubscribeMonitorPool)message );
        }
        else if ( message instanceof UnsubscribeMonitorPool )
        {
            handleUnsubscribeMonitorPool ( (UnsubscribeMonitorPool)message );
        }
        else if ( message instanceof CloseQuery )
        {
            handleCloseQuery ( (CloseQuery)message );
        }
        else if ( message instanceof CreateQuery )
        {
            handleCreateQuery ( (CreateQuery)message );
        }
        else if ( message instanceof LoadMore )
        {
            handleLoadMore ( (LoadMore)message );
        }
        else if ( message instanceof AcknowledgeRequest )
        {
            handleAknRequest ( (AcknowledgeRequest)message );
        }
        else
        {
            super.messageReceived ( message );
        }
    }

    private void handleAknRequest ( final AcknowledgeRequest message ) throws InvalidSessionException
    {
        final Date timestamp = message.getAknTimestamp () == null ? null : new Date ( message.getAknTimestamp () );
        final org.openscada.sec.UserInformation userInformation = convertUserInformation ( message.getOperationParameters () );
        try
        {
            this.service.acknowledge ( this.session, message.getMonitorId (), timestamp, userInformation );
            sendMessage ( new AcknowledgeResponse ( new Response ( message.getRequest () ), null ) );
        }
        catch ( final PermissionDeniedException e )
        {
            sendMessage ( new AcknowledgeResponse ( new Response ( message.getRequest () ), new ErrorInformation ( 0x01L, "Permission denied", ExceptionHelper.formatted ( e ) ) ) );
        }
    }

    private org.openscada.sec.UserInformation convertUserInformation ( final OperationParameters operationParameters )
    {
        if ( operationParameters == null )
        {
            return null;
        }
        final org.openscada.core.data.UserInformation ui = operationParameters.getUserInformation ();
        if ( ui == null )
        {
            return null;
        }
        return new org.openscada.sec.UserInformation ( ui.getName (), null );
    }

    private void handleCreateQuery ( final CreateQuery message ) throws InvalidSessionException
    {
        final long queryId = message.getQueryId ();
        if ( this.queries.containsKey ( queryId ) )
        {
            throw new IllegalStateException ( String.format ( "Query Id %s already exists", queryId ) );
        }

        final QueryImpl query = new QueryImpl ( queryId, this );

        final Query queryHandle = this.service.createQuery ( this.session, message.getQueryType (), message.getQueryData (), query );
        query.setQuery ( queryHandle );
        this.queries.put ( queryId, query );
    }

    private void handleLoadMore ( final LoadMore message )
    {
        final QueryImpl query = this.queries.get ( message.getQueryId () );
        if ( query == null )
        {
            return;
        }
        query.loadMore ( message.getCount () );
    }

    private void handleCloseQuery ( final CloseQuery message )
    {
        final QueryImpl query = this.queries.get ( message.getQueryId () );
        if ( query == null )
        {
            return;
        }
        query.close ();
    }

    private void handleSubscribeMonitorPool ( final SubscribeMonitorPool message )
    {
    }

    private void handleUnsubscribeMonitorPool ( final UnsubscribeMonitorPool message )
    {
    }

    private void handelStopBrowse ()
    {
        if ( this.browserListenerManager == null )
        {
            return;
        }

        this.session.setBrowserListener ( null );
        this.browserListenerManager = null;
    }

    private void handleStartBrowse ()
    {
        if ( this.browserListenerManager != null )
        {
            return;
        }

        this.browserListenerManager = new BrowserListenerManager ( this );
        this.session.setBrowserListener ( this.browserListenerManager );
    }

    public synchronized void sendQueryData ( final QueryImpl queryImpl, final List<Event> events )
    {
        final QueryImpl query = this.queries.get ( queryImpl.getQueryId () );
        if ( query == null )
        {
            return;
        }

        sendMessage ( new UpdateQueryData ( query.getQueryId (), convert ( events ) ) );
    }

    public synchronized void sendQueryState ( final QueryImpl queryImpl, final QueryState state, final Throwable error )
    {
        final QueryImpl query = this.queries.get ( queryImpl.getQueryId () );
        if ( query == null )
        {
            return;
        }

        sendMessage ( new UpdateQueryState ( query.getQueryId (), state, new ErrorInformation ( null, error == null ? null : error.getMessage (), ExceptionHelper.formatted ( error ) ) ) );
    }

    private List<EventInformation> convert ( final List<Event> events )
    {
        final List<EventInformation> result = new ArrayList<EventInformation> ( events.size () );

        for ( final Event event : events )
        {
            result.add ( convertEvent ( event ) );
        }

        return result;
    }

    private EventInformation convertEvent ( final Event event )
    {
        return new EventInformation ( event.getId ().toString (), event.getSourceTimestamp ().getTime (), event.getEntryTimestamp ().getTime (), event.getAttributes () );
    }

    public synchronized void handleBrowseDataChanged ( final BrowserListenerManager browserListenerManager, final List<BrowserEntry> addedOrUpdated, final Set<String> removed, final boolean full )
    {
        if ( this.browserListenerManager != browserListenerManager )
        {
            return;
        }

        sendMessage ( new BrowseData ( addedOrUpdated, removed, full ) );
    }

}
