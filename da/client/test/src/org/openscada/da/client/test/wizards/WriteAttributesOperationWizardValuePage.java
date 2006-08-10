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


package org.openscada.da.client.test.wizards;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;
import org.eclipse.jface.viewers.CellEditor;
import org.eclipse.jface.viewers.ColumnWeightData;
import org.eclipse.jface.viewers.ComboBoxCellEditor;
import org.eclipse.jface.viewers.ICellModifier;
import org.eclipse.jface.viewers.IStructuredContentProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITableLabelProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.TableLayout;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TextCellEditor;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.wizard.IWizardPage;
import org.eclipse.jface.wizard.WizardPage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ModifyEvent;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.openscada.da.client.test.impl.DataItemEntry;
import org.openscada.da.client.test.impl.HiveConnection;
import org.openscada.da.core.data.NotConvertableException;
import org.openscada.da.core.data.NullValueException;
import org.openscada.da.core.data.Variant;

class WriteAttributesOperationWizardValuePage extends WizardPage implements IWizardPage
{
    private static Logger _log = Logger.getLogger ( WriteAttributesOperationWizardValuePage.class );

    private Text _itemIdText = null;
    
    private IStructuredSelection _selection = null;
    
    private Color _defaultValueColor = null;
    
    private HiveConnection _connection = null;
    
    private TableViewer _table = null;
    

    private enum ValueType
    {
        NULL ( 0, "NULL" )
        {
            public Variant convertTo ( String value )
            {
                return new Variant();
            }
        },
        STRING ( 1, "string" )
        {
            public Variant convertTo ( String value )
            {
                return new Variant ( value );
            }
        },
        INT ( 2, "32 bit signed integer" )
        {
            public Variant convertTo ( String value ) throws NotConvertableException
            {
                Variant stringValue = new Variant ( value );
                try
                {
                    return new Variant ( stringValue.asInteger () );
                }
                catch ( NullValueException e )
                {
                    return new Variant ();
                }
            }
        },
        LONG ( 3, "64 bit signed integer" )
        {
            public Variant convertTo ( String value ) throws NotConvertableException
            {
                Variant stringValue = new Variant ( value );
                try
                {
                    return new Variant ( stringValue.asLong () );
                }
                catch ( NullValueException e )
                {
                    return new Variant ();
                }
            }
        },
        DOUBLE ( 4, "double floating point" )
        {
            public Variant convertTo ( String value ) throws NotConvertableException
            {
                Variant stringValue = new Variant ( value );
                try
                {
                    return new Variant ( stringValue.asDouble () );
                }
                catch ( NullValueException e )
                {
                    return new Variant ();
                }
            }
        },
        /*
        BOOLEAN ( 5, "boolean" )
        {
            public Variant convertTo ( String value ) throws NotConvertableException
            {
                Variant stringValue = new Variant ( value );
                try
                {
                    return new Variant ( stringValue.asBoolean () );
                }
                catch ( NullValueException e )
                {
                    return new Variant ();
                }
            }
        },*/
        ;
        
        private int _index;
        private String _label;
        
        ValueType ( int index, String label )
        {
            _index = index;
            _label = label;
        }
        
        public String label () { return _label; }
        public int index () { return _index; }
        public abstract Variant convertTo ( String value ) throws NotConvertableException;
    }
    
    
    private class AttributeEntry
    {
        private String _name = "";
        private ValueType _valueType = ValueType.STRING;
        private String _valueString = "";
        private Variant _value = new Variant ();
        private Throwable _valueError = null;
        
        public AttributeEntry ( String name, ValueType valueType, String value )
        {
            super ();
            _name = name;
            _valueType = valueType;
            setValue ( value );
        }
        
        public String getName ()
        {
            return _name;
        }
        public void setName ( String name )
        {
            _name = name;
        }
        public Variant getValue ()
        {
            return _value;
        }
        
        public String getValueString ()
        {
            return _valueString;
        }
        
        public void setValue ( String value )
        {
           try
           {
               _valueString = value;
               _value = _valueType.convertTo ( value );
               _valueError = null;
           }
           catch ( Exception e )
           {
               _valueError = e;
           }
        }

        public ValueType getValueType ()
        {
            return _valueType;
        }

        public void setValueType ( ValueType valueType )
        {
            _valueType = valueType;
            setValue ( _valueString );
        }

        public Throwable getValueError ()
        {
            return _valueError;
        }
    }
    
    private class Attributes
    {
        private List<AttributeEntry> _entries = new ArrayList<AttributeEntry> ();

        public void add ( AttributeEntry entry )
        {
            _entries.add (  entry );
        }
        
        public List<AttributeEntry> getEntries ()
        {
            return _entries;
        }
    }
    
    private class MyLabelProvider extends LabelProvider implements ITableLabelProvider
    {

        public Image getColumnImage ( Object element, int columnIndex )
        {
            return getImage ( element );
        }

        public String getColumnText ( Object element, int columnIndex )
        {
            _log.info ( "Label for: " + element + ":" + columnIndex );
            
            if ( element instanceof AttributeEntry )
            {
                AttributeEntry entry = (AttributeEntry)element;
                _log.info ( "Label: " + entry.getName () );
                switch ( columnIndex )
                {
                case 0:
                    return entry.getName ();
                case 1:
                    return entry.getValueType ().toString ();
                case 2:
                    return entry.getValue ().asString ( "<null>" );
                case 3:
                {
                    if ( entry.getValueError () != null )
                    {
                        return entry.getValueError ().getMessage ();
                    }
                    return "";
                }
                }
            }
            return getText ( element );
        }
        
    }
    
    private class MyContentProvider implements IStructuredContentProvider
    {
        public Object[] getElements ( Object inputElement )
        {
            if ( inputElement instanceof Attributes )
            {
                Attributes attributes = (Attributes)inputElement;
                return attributes.getEntries ().toArray ( new AttributeEntry[0] );
            }
            return new Object[0];
        }

        public void dispose ()
        {
        }

        public void inputChanged ( Viewer viewer, Object oldInput, Object newInput )
        {
        }
        
    }
    
    private ComboBoxCellEditor _valueTypeEditor;
    private String [] PROPERTIES = new String [] { "name", "value-type", "value", "value-error" };
    
    private class MyCellModifier implements ICellModifier
    {
        private TableViewer _viewer = null;
        
        public MyCellModifier ( TableViewer viewer )
        {
            _viewer = viewer;
        }
        
        public boolean canModify ( Object element, String property )
        {
            _log.debug ( "Can modify: " + element + ":" + property );
            
            if ( element instanceof AttributeEntry )
            {
                if ( property.equals ( "value" ) )
                    return true;
                if ( property.equals ( "name" ) )
                    return true;
                if ( property.equals ( "value-type" ) )
                    return true;
            }
            return false;
        }

        public Object getValue ( Object element, String property )
        {
            _log.debug ( "Get Value: " + element + ":" + property );
            
            if ( element instanceof AttributeEntry )
            {
                AttributeEntry entry = (AttributeEntry)element;
                if ( property.equals ( "value" ) )
                    return entry.getValueString ();
                if ( property.equals ( "name" ) )
                    return entry.getName ();
                if ( property.equals ( "value-type" ) )
                {
                    return entry.getValueType ().index ();
                }
            }
            return null;  
        }

        public void modify ( Object element, String property, Object value )
        {
            _log.debug ( "Modify Value: " + element + ":" + property + ":" + value );
            
            TableItem tableItem = (TableItem) element;

            if ( tableItem.getData() instanceof AttributeEntry )
            {
                AttributeEntry entry = (AttributeEntry)tableItem.getData();
                if ( property.equals ( "value" ) )
                {
                    entry.setValue ( value.toString () );
                }
                if ( property.equals ( "name" ) )
                {
                    entry.setName ( value.toString () );
                }
                if ( property.equals ( "value-type" ) )
                {
                    Integer i = (Integer)value;
                    String valueType = _valueTypeEditor.getItems ()[i];
                    for ( ValueType vt : ValueType.values () )
                    {
                        if ( vt.label ().equals ( valueType ) )
                            entry.setValueType ( vt );
                    }
                }
                _viewer.update ( entry, PROPERTIES );
            }
        }
        
    }
    private Attributes _attributes = new Attributes ();
    
    protected WriteAttributesOperationWizardValuePage (  )
    {
        super ( "wizardPage" );
        setTitle ( "Write Data Item" );
        setDescription ( "Enter the information to write" );
    }

    public void createControl ( Composite parent )
    {
        Composite container = new Composite ( parent, SWT.NONE );
        
        GridLayout layout = new GridLayout();
        container.setLayout(layout);
        layout.numColumns = 3;
        layout.verticalSpacing = 9;
        
        
        Label label = new Label ( container, SWT.NONE );
        label.setText("&Item:");

        _itemIdText = new Text ( container, SWT.BORDER | SWT.SINGLE );
        GridData gd = new GridData ( GridData.FILL_HORIZONTAL );
        _itemIdText.setLayoutData ( gd );
        _itemIdText.addModifyListener ( new ModifyListener() {
            public void modifyText(ModifyEvent e)
            {
                dialogChanged();
            }
        });
        
        label = new Label ( container, SWT.NONE );
       
        // row 2
        
        _attributes.add ( new AttributeEntry ( "test", ValueType.STRING, "1.23" ) );
        
        gd = new GridData ( GridData.FILL_BOTH );
        gd.horizontalSpan = 3;
        gd.grabExcessHorizontalSpace = true;
        gd.grabExcessVerticalSpace = true;
        _table = new TableViewer ( container, SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL );
        
        TableColumn col;
        
        col = new TableColumn (_table.getTable (), SWT.NONE );
        col.setText ( "Name" );
        col = new TableColumn (_table.getTable (), SWT.NONE );
        col.setText ( "Value Type" );
        col = new TableColumn (_table.getTable (), SWT.NONE );
        col.setText ( "Value" );
        col = new TableColumn (_table.getTable (), SWT.NONE );
        col.setText ( "Value Error" );
        _table.getTable ().setHeaderVisible ( true );
        
        try
        {
            _table.setLabelProvider ( new MyLabelProvider () );
            _table.setContentProvider ( new MyContentProvider () );
            
            
            _table.setColumnProperties ( PROPERTIES );
            _table.setCellModifier ( new MyCellModifier ( _table ) );
            
            TextCellEditor nameEditor = new TextCellEditor ( _table.getTable () );
            
            List<String> values = new LinkedList<String> ();
            for ( ValueType vt : ValueType.values () )
            {
                values.add ( vt.label () );   
            }
            _valueTypeEditor = new ComboBoxCellEditor ( _table.getTable (), values.toArray ( new String [0] )  );
            
            TextCellEditor valueEditor = new TextCellEditor ( _table.getTable () );
            _table.setCellEditors ( new CellEditor[] { nameEditor, _valueTypeEditor, valueEditor, new TextCellEditor ( _table.getTable () ) } );
            
            TableLayout tableLayout = new TableLayout();
            tableLayout.addColumnData ( new ColumnWeightData ( 50, 75, true ) );
            tableLayout.addColumnData ( new ColumnWeightData ( 50, 75, true ) );
            tableLayout.addColumnData ( new ColumnWeightData ( 50, 75, true ) );
            tableLayout.addColumnData ( new ColumnWeightData ( 50, 75, true ) );
            _table.getTable ().setLayout ( tableLayout );
            
            _table.setInput ( _attributes );
        }
        catch ( Exception e )
        {
            _log.warn ( "Unable to create control", e );
        }
        
        _table.getTable ().setLayoutData ( gd );
        //_table.getTable ().pack ();
        
        setControl ( container );
        fillFromSelection ();
        dialogChanged ();
    }

    private void fillFromSelection ()
    {
        if ( _selection == null )
            return;
        
        Object obj = _selection.getFirstElement ();
        if ( obj == null )
            return;
        if ( !(obj instanceof DataItemEntry) )
            return;
        
        _itemIdText.setText ( ((DataItemEntry)obj).getId () );
    }
    
    private void dialogChanged ()
    {
        // connection
        if ( _connection == null )
        {
            updateStatus ( "No hive connection selection" );
            return;
        }
        
        // item
        if ( _itemIdText.getText ().length () <= 0 )
        {
            updateStatus ( "Item name must not be empty" );
            return;
        }

        updateStatus ( null );
    }

    private void updateStatus ( String message )
    {
        setErrorMessage ( message );
        setPageComplete ( message == null );
    }
    
    public String getItem ()
    {
        return _itemIdText.getText ();
    }
    
    public Map<String, Variant> getAttributes ()
    {
        Map<String, Variant> attributes = new HashMap<String, Variant> ();
        
        // FIXME: fill map
        
        return attributes;
    }
    
    public HiveConnection getConnection()
    {
        return _connection;
    }

    public void setSelection ( IStructuredSelection selection )
    {
        _selection = selection;
        
        Object obj = _selection.getFirstElement ();
        if ( obj instanceof HiveConnection )
            _connection = (HiveConnection)obj;
        else if ( obj instanceof DataItemEntry )
            _connection = ((DataItemEntry)obj).getConnection ();
    }
}