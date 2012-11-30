/*
 * Copyright 2012 DTO Labs, Inc. (http://dtolabs.com)
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

/*
* AbstractBasePluginTest.java
* 
* User: Greg Schueler <a href="mailto:greg@dtosolutions.com">greg@dtosolutions.com</a>
* Created: 11/29/12 3:22 PM
* 
*/
package com.dtolabs.rundeck.plugins.step;

import com.dtolabs.rundeck.core.plugins.Plugin;
import com.dtolabs.rundeck.core.plugins.configuration.ConfigurationException;
import com.dtolabs.rundeck.core.plugins.configuration.Description;
import com.dtolabs.rundeck.core.plugins.configuration.Property;
import com.dtolabs.rundeck.plugins.descriptions.PluginDescription;
import com.dtolabs.rundeck.plugins.descriptions.PluginProperty;
import com.dtolabs.rundeck.plugins.descriptions.SelectValues;
import junit.framework.TestCase;

import java.lang.reflect.Field;
import java.util.*;


/**
 * AbstractBasePluginTest is ...
 *
 * @author Greg Schueler <a href="mailto:greg@dtosolutions.com">greg@dtosolutions.com</a>
 */
public class AbstractBasePluginTest extends TestCase {
    //test reflection of property values

    /**
     * invalid: doesn't have @Plugin annotation
     */
    static class invalidTest1 extends AbstractBasePlugin {
        @PluginProperty
        private String testString;
        @PluginProperty(title = "test2", description = "testdesc2")
        private String testString2;
        @PluginProperty(name = "test3", title = "test3", description = "testdesc3")
        private String testString3;
    }

    public void testInvalid() {
        invalidTest1 test = new invalidTest1();
        Description description = test.getDescription();
        assertNull(description);
    }

    /**
     * basic annotation test
     */
    @Plugin(name = "basicTest1", service = "x")
    static class basicTest1 extends AbstractBasePlugin {

    }

    public void testBasic1() {
        basicTest1 test = new basicTest1();
        Description description = test.getDescription();
        assertNotNull(description);
        assertNotNull(description.getName());
        assertEquals("basicTest1", description.getName());
        assertEquals("basicTest1", description.getTitle());
        assertEquals("", description.getDescription());
        assertNotNull(description.getProperties());
        assertEquals(0, description.getProperties().size());
    }

    /**
     * basic annotation test2, has PluginDescription
     */
    @Plugin(name = "basicTest2", service = "x")
    @PluginDescription(title = "basictest2 title", description = "basicTest Description")
    static class basicTest2 extends AbstractBasePlugin {

    }

    public void testBasic2() {
        basicTest2 test = new basicTest2();
        Description description = test.getDescription();
        assertNotNull(description);
        assertNotNull(description.getName());
        assertEquals("basicTest2", description.getName());
        assertEquals("basictest2 title", description.getTitle());
        assertEquals("basicTest Description", description.getDescription());

        assertNotNull(description.getProperties());
        assertEquals(0, description.getProperties().size());
    }

    /**
     * string property test
     */
    @Plugin(name = "stringTest1", service = "x")
    static class stringTest1 extends AbstractBasePlugin {
        @PluginProperty
        private String testString;
        @PluginProperty(title = "test2", description = "testdesc2")
        private String testString2;
        @PluginProperty(name = "test3", title = "test3title", description = "testdesc3")
        private String testString3;
        @PluginProperty(defaultValue = "elf1")
        private String testString4;
        @PluginProperty(required = true)
        private String testString5;
    }

    public void testPropertiesStringDefault() {
        stringTest1 test1 = new stringTest1();
        Description description = test1.getDescription();
        assertNotNull(description);
        assertEquals("stringTest1", description.getName());
        assertNotNull(description.getProperties());
        assertEquals(5, description.getProperties().size());
        HashMap<String, Property> map = mapOfProperties(description);
        assertTrue(map.containsKey("testString"));
        assertTrue(map.containsKey("testString2"));
        assertTrue(map.containsKey("test3"));
        assertTrue(map.containsKey("testString4"));
        assertTrue(map.containsKey("testString5"));
    }

    /**
     * Default annotation values
     */
    public void testPropertiesStringAnnotationsDefault() {
        stringTest1 test1 = new stringTest1();
        Description description = test1.getDescription();
        HashMap<String, Property> map = mapOfProperties(description);

        Property p1 = map.get("testString");
        assertEquals(Property.Type.String, p1.getType());
        assertEquals(null, p1.getDefaultValue());
        assertEquals("", p1.getDescription());
        assertEquals("testString", p1.getTitle());
        assertEquals(null, p1.getSelectValues());
        assertEquals(null, p1.getValidator());
        assertEquals(false, p1.isRequired());
    }

    /**
     * Default annotation values
     */
    public void testPropertiesStringAnnotationsTitle() {
        stringTest1 test1 = new stringTest1();
        Description description = test1.getDescription();
        HashMap<String, Property> map = mapOfProperties(description);

        Property p1 = map.get("testString2");
        assertEquals(Property.Type.String, p1.getType());
        assertEquals(null, p1.getDefaultValue());
        assertEquals("testdesc2", p1.getDescription());
        assertEquals("test2", p1.getTitle());
        assertEquals(null, p1.getSelectValues());
        assertEquals(null, p1.getValidator());
        assertEquals(false, p1.isRequired());
    }

    /**
     * Default annotation values
     */
    public void testPropertiesStringAnnotationsName() {
        stringTest1 test1 = new stringTest1();
        Description description = test1.getDescription();
        HashMap<String, Property> map = mapOfProperties(description);

        Property p1 = map.get("test3");
        assertEquals(Property.Type.String, p1.getType());
        assertEquals(null, p1.getDefaultValue());
        assertEquals("testdesc3", p1.getDescription());
        assertEquals("test3title", p1.getTitle());
        assertEquals(null, p1.getSelectValues());
        assertEquals(null, p1.getValidator());
        assertEquals(false, p1.isRequired());
    }

    /**
     * Default annotation values
     */
    public void testPropertiesStringAnnotationsDefaultValue() {
        stringTest1 test1 = new stringTest1();
        Description description = test1.getDescription();
        HashMap<String, Property> map = mapOfProperties(description);

        Property p1 = map.get("testString4");
        assertEquals("elf1", p1.getDefaultValue());
    }

    /**
     * Default annotation values
     */
    public void testPropertiesStringAnnotationsRequired() {
        stringTest1 test1 = new stringTest1();
        Description description = test1.getDescription();
        HashMap<String, Property> map = mapOfProperties(description);

        Property p1 = map.get("testString5");
        assertEquals(true, p1.isRequired());
    }

    private HashMap<String, Property> mapOfProperties(Description description) {
        List<Property> properties = description.getProperties();
        HashMap<String, Property> map = new HashMap<String, Property>();
        for (final Property property : properties) {
            assertFalse(map.containsKey(property.getName()));
            map.put(property.getName(), property);
        }
        return map;
    }


    /**
     * test property types
     */
    @Plugin(name = "typeTest1", service = "x")
    static class typeTest1 extends AbstractBasePlugin {
        @PluginProperty
        private String testString;
        @PluginProperty
        private Boolean testbool1;
        @PluginProperty
        private boolean testbool2;
        @PluginProperty
        private int testint1;
        @PluginProperty
        private Integer testint2;
        @PluginProperty
        private long testlong1;
        @PluginProperty
        private Long testlong2;
    }

    public void testFieldTypesString() throws Exception {
        typeTest1 test = new typeTest1();
        assertNotNull(test.getDescription());
        HashMap<String, Property> map = mapOfProperties(test.getDescription());
        assertPropertyType(map, "testString", Property.Type.String);
        assertPropertyType(map, "testbool1", Property.Type.Boolean);
        assertPropertyType(map, "testbool2", Property.Type.Boolean);
        assertPropertyType(map, "testint1", Property.Type.Integer);
        assertPropertyType(map, "testint2", Property.Type.Integer);
        assertPropertyType(map, "testlong1", Property.Type.Long);
        assertPropertyType(map, "testlong2", Property.Type.Long);
    }

    private void assertPropertyType(HashMap<String, Property> map, String fieldName, Property.Type fieldType) {
        Property prop1 = map.get(fieldName);
        assertNotNull(prop1);
        assertEquals(fieldType, prop1.getType());
    }


    /**
     * test property types
     */
    @Plugin(name = "typeSelect1", service = "x")
    static class typeSelect1 extends AbstractBasePlugin {
        @PluginProperty
        @SelectValues(values = {"a", "b"})
        private String testSelect1;
        @PluginProperty
        @SelectValues(values = {"a", "b", "c"}, freeSelect = true)
        private String testSelect2;
    }

    public void testSelectFields() {
        typeSelect1 test = new typeSelect1();
        assertNotNull(test.getDescription());
        HashMap<String, Property> map = mapOfProperties(test.getDescription());
        assertPropertyType(map, "testSelect1", Property.Type.Select);
        Property select1 = map.get("testSelect1");
        assertNotNull(select1.getSelectValues());
        assertEquals(2, select1.getSelectValues().size());
        assertEquals(Arrays.asList("a", "b"), select1.getSelectValues());

        assertPropertyType(map, "testSelect2", Property.Type.FreeSelect);
        Property select2 = map.get("testSelect2");
        assertNotNull(select2.getSelectValues());
        assertEquals(3, select2.getSelectValues().size());
        assertEquals(Arrays.asList("a", "b", "c"), select2.getSelectValues());
    }

    /**
     * test property types
     */
    @Plugin(name = "typeTest1", service = "x")
    static class configuretest1 extends AbstractBasePlugin {
        @PluginProperty
        String testString;
        @PluginProperty
        @SelectValues(values = {"a", "b", "c"})
        String testSelect1;
        @PluginProperty
        @SelectValues(values = {"a", "b", "c"}, freeSelect = true)
        String testSelect2;
        @PluginProperty
        Boolean testbool1;
        @PluginProperty
        boolean testbool2;
        @PluginProperty
        int testint1;
        @PluginProperty
        Integer testint2;
        @PluginProperty
        long testlong1;
        @PluginProperty
        Long testlong2;
    }

    public void testConfigureDescribedPropertiesEmpty() throws Exception {
        configuretest1 test = new configuretest1();
        assertNull(test.testString);
        assertNull(test.testSelect1);
        assertNull(test.testSelect2);
        assertNull(test.testbool1);
        assertFalse(test.testbool2);
        assertEquals(0, test.testint1);
        assertNull(test.testint2);
        assertEquals(0, test.testlong1);
        assertNull(test.testlong2);
        test.configureDescribedProperties(new HashMap<String, Object>());
        assertNull(test.testString);
        assertNull(test.testSelect1);
        assertNull(test.testSelect2);
        assertNull(test.testbool1);
        assertFalse(test.testbool2);
        assertEquals(0, test.testint1);
        assertNull(test.testint2);
        assertEquals(0, test.testlong1);
        assertNull(test.testlong2);
    }

    public void testConfigureDescribedPropertiesString() throws Exception {
        configuretest1 test = new configuretest1();
        HashMap<String, Object> configuration = new HashMap<String, Object>();
        configuration.put("testString", "monkey");
        configuration.put("testSelect1", "a");
        configuration.put("testSelect2", "b");
        test.configureDescribedProperties(configuration);
        assertEquals("monkey", test.testString);
        assertEquals("a", test.testSelect1);
        assertEquals("b", test.testSelect2);
    }
    public void testConfigureDescribedPropertiesSelectAllowed() throws Exception {
        configuretest1 test = new configuretest1();
        String[] values = {"a", "b", "c"};
        for (final String value : values) {
            HashMap<String, Object> configuration = new HashMap<String, Object>();
            configuration.put("testSelect1", value);
            configuration.put("testSelect2", value);
            test.configureDescribedProperties(configuration);
            assertEquals(value, test.testSelect1);
            assertEquals(value, test.testSelect2);
        }
    }
    public void testConfigureDescribedPropertiesSelectInvalidSelect() throws Exception{
        configuretest1 test = new configuretest1();
        //invalid for select field
        String[] invalid = {"monkey", "spaghetti", "wheel"};
        for (final String value : invalid) {
            HashMap<String, Object> config = new HashMap<String, Object>();
            config.put("testSelect1", value);
            try {
                test.configureDescribedProperties(config);
                fail("Should not allow value: " + value);
            } catch (RuntimeException e) {
                e.printStackTrace();
            }
            assertNull(test.testSelect1);
        }
    }

    public void testConfigureDescribedPropertiesSelectInvalidFreeSelect() throws Exception {
        configuretest1 test = new configuretest1();
        //invalid for select field
        String[] invalid = {"monkey", "spaghetti", "wheel"};
        for (final String value : invalid) {
            HashMap<String, Object> config = new HashMap<String, Object>();
            config.put("testSelect2", value);
            test.configureDescribedProperties(config);
            assertEquals(value,test.testSelect2);
        }
    }

    public void testConfigureDescribedPropertiesBool() throws Exception {
        configuretest1 test = new configuretest1();
        HashMap<String, Object> configuration = new HashMap<String, Object>();
        //true value string
        configuration.put("testbool1", "true");
        configuration.put("testbool2", "true");
        test.configureDescribedProperties(configuration);
        assertTrue(test.testbool1);
        assertTrue(test.testbool2);

        //false value string
        configuration.put("testbool1", "false");
        configuration.put("testbool2", "false");
        test.configureDescribedProperties(configuration);
        assertFalse(test.testbool1);
        assertFalse(test.testbool2);

        test.testbool1=true;
        test.testbool2=true;

        //other value string
        configuration.put("testbool1", "monkey");
        configuration.put("testbool2", "elf");
        test.configureDescribedProperties(configuration);
        assertFalse(test.testbool1);
        assertFalse(test.testbool2);
    }
    public void testConfigureDescribedPropertiesInt() throws Exception {
        configuretest1 test = new configuretest1();
        HashMap<String, Object> configuration = new HashMap<String, Object>();
        //int values
        configuration.put("testint1", "1");
        configuration.put("testint2", "2");
        test.configureDescribedProperties(configuration);
        assertEquals(1, test.testint1);
        assertEquals(2, (int) test.testint2);

        //invalid values
        configuration.put("testint1", "asdf");
        configuration.put("testint2", "fdjkfd");
        try {
            test.configureDescribedProperties(configuration);
            fail("shouldn't succeed");
        } catch (NumberFormatException e) {
            e.printStackTrace();
        }
        assertEquals(1, test.testint1);
        assertEquals(2, (int) test.testint2);
    }
    public void testConfigureDescribedPropertiesLong() throws Exception {
        configuretest1 test = new configuretest1();
        HashMap<String, Object> configuration = new HashMap<String, Object>();
        //int values
        configuration.put("testlong1", "1");
        configuration.put("testlong2", "2");
        test.configureDescribedProperties(configuration);
        assertEquals(1,test.testlong1);
        assertEquals(2,(long)test.testlong2);

        //invalid values
        configuration.put("testlong1", "asdf");
        configuration.put("testlong2", "fdjkfd");
        try {
            test.configureDescribedProperties(configuration);
            fail("shouldn't succeed");
        } catch (NumberFormatException e) {
            e.printStackTrace();
        }
        assertEquals(1,test.testlong1);
        assertEquals(2,(long)test.testlong2);
    }
}
