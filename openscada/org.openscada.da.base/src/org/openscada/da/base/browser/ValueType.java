package org.openscada.da.base.browser;

import org.openscada.core.NotConvertableException;
import org.openscada.core.NullValueException;
import org.openscada.core.Variant;
import org.openscada.rcp.da.client.Activator;

/**
 * value types used for visual input purposes
 * @author Jens Reimann
 *
 */
public enum ValueType
{
    NULL ( 10, "NULL" )
    {
        @Override
        public Variant convertTo ( final String value )
        {
            return new Variant ();
        }
    },
    STRING ( 20, "string" )
    {
        @Override
        public Variant convertTo ( String value )
        {
            value = value.replace ( Activator.NATIVE_LS, "\n" );
            return new Variant ( value );
        }
    },
    STRING_CRLF ( 21, "string (crlf)" )
    {
        @Override
        public Variant convertTo ( String value )
        {
            value = value.replace ( Activator.NATIVE_LS, "\r\n" );
            return new Variant ( value );
        }
    },
    INT ( 30, "32 bit signed integer" )
    {
        @Override
        public Variant convertTo ( final String value ) throws NotConvertableException
        {
            final Variant stringValue = new Variant ( value );
            try
            {
                return new Variant ( stringValue.asInteger () );
            }
            catch ( final NullValueException e )
            {
                return new Variant ();
            }
        }
    },
    LONG ( 40, "64 bit signed integer" )
    {
        @Override
        public Variant convertTo ( final String value ) throws NotConvertableException
        {
            final Variant stringValue = new Variant ( value );
            try
            {
                return new Variant ( stringValue.asLong () );
            }
            catch ( final NullValueException e )
            {
                return new Variant ();
            }
        }
    },
    DOUBLE ( 50, "double floating point" )
    {
        @Override
        public Variant convertTo ( final String value ) throws NotConvertableException
        {
            final Variant stringValue = new Variant ( value );
            try
            {
                return new Variant ( stringValue.asDouble () );
            }
            catch ( final NullValueException e )
            {
                return new Variant ();
            }
        }
    },
    BOOLEAN ( 60, "boolean" )
    {
        @Override
        public Variant convertTo ( final String value ) throws NotConvertableException
        {
            final Variant stringValue = new Variant ( value );
            return new Variant ( stringValue.asBoolean () );
        }
    },
    ;

    private int _index;

    private String _label;

    ValueType ( final int index, final String label )
    {
        this._index = index;
        this._label = label;
    }

    public String label ()
    {
        return this._label;
    }

    public int index ()
    {
        return this._index;
    }

    public abstract Variant convertTo ( String value ) throws NotConvertableException;
}