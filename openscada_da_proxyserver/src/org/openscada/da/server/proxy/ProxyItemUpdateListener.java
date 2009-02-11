/*
 * This file is part of the OpenSCADA project
 * Copyright (C) 2006-2009 inavare GmbH (http://inavare.com)
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

package org.openscada.da.server.proxy;

import java.util.Map;

import org.openscada.core.Variant;
import org.openscada.core.subscription.SubscriptionState;
import org.openscada.da.client.ItemUpdateListener;
import org.openscada.da.server.common.AttributeMode;

class ProxyItemUpdateListener implements ItemUpdateListener
{
    private final ProxyDataItem item;

    private final ProxySubConnection subConnection;

    public ProxyItemUpdateListener ( final ProxyDataItem item, final ProxySubConnection subConnection )
    {
        this.item = item;
        this.subConnection = subConnection;
    }

    @Override
    public void notifyDataChange ( final Variant value, final Map<String, Variant> attributes, final boolean cache )
    {
        this.item.getProxyValueHolder ().updateData ( this.subConnection.getId (), value, attributes, cache ? AttributeMode.SET : AttributeMode.UPDATE );
    }

    @Override
    public void notifySubscriptionChange ( final SubscriptionState subscriptionState, final Throwable subscriptionError )
    {
        this.item.getProxyValueHolder ().updateSubscriptionState ( this.subConnection.getId (), subscriptionState, subscriptionError );
    }
}