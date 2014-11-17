package com.capgemini.cobigen.javaplugin.inputreader;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.sf.mmm.util.pojo.descriptor.api.PojoDescriptor;
import net.sf.mmm.util.pojo.descriptor.api.PojoDescriptorBuilder;
import net.sf.mmm.util.pojo.descriptor.api.PojoPropertyDescriptor;
import net.sf.mmm.util.pojo.descriptor.api.accessor.PojoPropertyAccessor;
import net.sf.mmm.util.pojo.descriptor.api.accessor.PojoPropertyAccessorNonArgMode;
import net.sf.mmm.util.pojo.descriptor.api.accessor.PojoPropertyAccessorOneArgMode;
import net.sf.mmm.util.pojo.descriptor.impl.PojoDescriptorBuilderFactoryImpl;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.capgemini.cobigen.util.StringUtil;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

/**
 * The {@link ReflectedJavaModelBuilder} creates a new model for a given input pojo class
 *
 * @author mbrunnli (12.03.2013)
 */
public class ReflectedJavaModelBuilder {

    /**
     * Assigning logger to JavaModelBuilder
     */
    private static final Logger LOG = LoggerFactory.getLogger(ReflectedJavaModelBuilder.class);

    /**
     * Cached input pojo class in order to avoid unnecessary efforts
     */
    private Class<?> cachedPojo;

    /**
     * Cached model related to the cached input pojo
     */
    private Map<String, Object> cachedModel;

    /**
     * Creates the object model for the template instantiation.
     *
     * @param pojo
     *            {@link Class} object of the pojo all information should be retrieved from
     * @return A {@link Map} of a {@link String} key to {@link Object} mapping keys as described before to the
     *         corresponding information. Learn more about the FreeMarker data model at http
     *         ://freemarker.sourceforge.net/docs/dgui_quickstart.html
     * @author mbrunnli (06.02.2013)
     */
    Map<String, Object> createModel(final Class<?> pojo) {

        if (cachedPojo != null && cachedPojo.equals(pojo)) {
            return new HashMap<>(cachedModel);
        }
        cachedPojo = pojo;

        cachedModel = new HashMap<>();
        Map<String, Object> pojoModel = new HashMap<>();
        pojoModel.put(ModelConstant.NAME, pojo.getSimpleName());
        if (pojo.getPackage() != null) {
            pojoModel.put(ModelConstant.PACKAGE, pojo.getPackage().getName());
        } else {
            pojoModel.put(ModelConstant.PACKAGE, "");
        }
        pojoModel.put(ModelConstant.CANONICAL_NAME, pojo.getCanonicalName());

        Map<String, Object> annotations = new HashMap<>();
        extractAnnotationsRecursively(annotations, pojo.getAnnotations());
        pojoModel.put(ModelConstant.ANNOTATIONS, annotations);

        List<Map<String, Object>> attributes = extractFields(pojo);
        pojoModel.put(ModelConstant.FIELDS, attributes);
        determinePojoIds(pojo, attributes);
        collectAnnotations(pojo, attributes);

        List<Map<String, Object>> accessibleAttributes = extractMethodAccessibleFields(pojo);
        pojoModel.put(ModelConstant.METHOD_ACCESSIBLE_FIELDS, accessibleAttributes);

        Map<String, Object> superclass = extractSuperclass(pojo);
        pojoModel.put(ModelConstant.EXTENDED_TYPE, superclass);

        List<Map<String, Object>> interfaces = extractInterfaces(pojo);
        pojoModel.put(ModelConstant.IMPLEMENTED_TYPES, interfaces);

        pojoModel.put(ModelConstant.METHODS, extractMethods(pojo));
        cachedModel.put(ModelConstant.ROOT, pojoModel);

        return new HashMap<>(cachedModel);
    }

    /**
     * Extracts all fields from the given pojo, which are visible by using setter and getter methods
     * @param pojo
     *            source {@link Class} to determine all fields accessible via methods from
     * @return a list of field properties equivalently to {@link #extractFields(Class)}
     * @author mbrunnli (14.11.2014)
     */
    private List<Map<String, Object>> extractMethodAccessibleFields(Class<?> pojo) {
        PojoDescriptorBuilder pojoFieldDescriptorBuilder =
            PojoDescriptorBuilderFactoryImpl.getInstance().createPrivateFieldDescriptorBuilder();
        PojoDescriptor<?> pojoFieldDescriptor = pojoFieldDescriptorBuilder.getDescriptor(pojo);
        Collection<? extends PojoPropertyDescriptor> propertyFieldDescriptors =
            pojoFieldDescriptor.getPropertyDescriptors();
        Iterator<?> it = propertyFieldDescriptors.iterator();
        Map<String, Field> existingFields = Maps.newHashMap();
        while (it.hasNext()) {
            PojoPropertyDescriptor fieldDescriptor = (PojoPropertyDescriptor) it.next();
            if (!fieldDescriptor.getAccessors().isEmpty()) {
                existingFields.put(fieldDescriptor.getName(), (Field) fieldDescriptor.getAccessors()
                    .iterator().next().getAccessibleObject());
            }
        }

        PojoDescriptorBuilder pojoMethodDescriptorBuilder =
            PojoDescriptorBuilderFactoryImpl.getInstance().createPublicMethodDescriptorBuilder();
        PojoDescriptor<?> pojoMethodDescriptor = pojoMethodDescriptorBuilder.getDescriptor(pojo);
        Collection<? extends PojoPropertyDescriptor> propertyMethodDescriptors =
            pojoMethodDescriptor.getPropertyDescriptors();
        it = propertyMethodDescriptors.iterator();

        List<Map<String, Object>> fields = new LinkedList<>();
        while (it.hasNext()) {
            PojoPropertyDescriptor methodDescriptor = (PojoPropertyDescriptor) it.next();
            PojoPropertyAccessor getter = methodDescriptor.getAccessor(PojoPropertyAccessorNonArgMode.GET);
            PojoPropertyAccessor setter = methodDescriptor.getAccessor(PojoPropertyAccessorOneArgMode.SET);
            if (getter != null && setter != null) {
                if (existingFields.containsKey(getter.getName())) {
                    fields.add(extractFieldProperties(existingFields.get(getter.getName())));
                }
            }
        }
        return fields;
    }

    /**
     * Extracts the attributes from the given POJO
     *
     * @param pojo
     *            {@link Class} object of the POJO the data should be retrieved from
     * @return a {@link Set} of attributes, where each attribute is represented by a {@link Map} of a
     *         {@link String} key to the corresponding {@link String} value of meta information
     * @author mbrunnli (06.02.2013)
     */
    private List<Map<String, Object>> extractFields(Class<?> pojo) {

        List<Map<String, Object>> fields = new LinkedList<>();
        for (Field field : pojo.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            fields.add(extractFieldProperties(field));
        }
        return fields;
    }

    /**
     * Extracts all properties needed for model building from a given field
     * @param field
     *            the values should be extracted for
     * @return the mapping of property names to their values
     * @author mbrunnli (17.11.2014)
     */
    private Map<String, Object> extractFieldProperties(Field field) {
        Map<String, Object> fieldValues = new HashMap<>();
        fieldValues.put(ModelConstant.NAME, field.getName());
        // build type name with type parameters (parameter types cannot be retrieved, so use generic
        // parameter '?'
        String type = field.getType().getSimpleName();
        if (field.getType().getTypeParameters().length > 0) {
            type += "<";
            for (int i = 0; i < field.getType().getTypeParameters().length; i++) {
                if (!type.endsWith("<")) {
                    type += ",";
                }
                type += "?";
            }
            type += ">";
        }
        fieldValues.put(ModelConstant.TYPE, type);
        fieldValues.put(ModelConstant.CANONICAL_TYPE, field.getType().getCanonicalName());
        return fieldValues;
    }

    /**
     * Extracts the superclass from the given POJO
     *
     * @param pojo
     *            {@link Class} object of the POJO the supertype should be retrieved from
     * @return the supertype, represented by a {@link Map} of a {@link String} key to the corresponding
     *         {@link String} value of meta information
     * @author fkreis (24.09.2014)
     */
    private Map<String, Object> extractSuperclass(Class<?> pojo) {

        Map<String, Object> superclassModel = new HashMap<>();

        Class<?> superclass = pojo.getSuperclass();
        superclassModel.put(ModelConstant.NAME, superclass.getSimpleName());
        superclassModel.put(ModelConstant.CANONICAL_NAME, superclass.getCanonicalName());
        if (superclass.getPackage() != null) {
            superclassModel.put(ModelConstant.PACKAGE, superclass.getPackage().getName());
        } else {
            superclassModel.put(ModelConstant.PACKAGE, "");
        }

        return superclassModel;
    }

    /**
     * Extracts the implementedTypes (interfaces) from the given POJO
     *
     * @param pojo
     *            {@link Class} object of the POJO the interfaces should be retrieved from
     * @return a {@link Set} of implementedTypes (interfaces), where each is represented by a {@link Map} of a
     *         {@link String} key to the corresponding {@link String} value of meta information
     * @author fkreis (24.09.2014)
     */
    private List<Map<String, Object>> extractInterfaces(Class<?> pojo) {

        List<Map<String, Object>> interfaceList = new LinkedList<>();

        for (Class<?> c : pojo.getInterfaces()) {
            Map<String, Object> interfaceModel = new HashMap<>();
            interfaceModel.put(ModelConstant.NAME, c.getSimpleName());
            interfaceModel.put(ModelConstant.CANONICAL_NAME, c.getCanonicalName());
            if (c.getPackage() != null) {
                interfaceModel.put(ModelConstant.PACKAGE, c.getPackage().getName());
            } else {
                interfaceModel.put(ModelConstant.PACKAGE, "");
            }

            interfaceList.add(interfaceModel);
        }

        return interfaceList;
    }

    /**
     * Extracts all methods and their properties from the given java class
     *
     * @param pojo
     *            java class
     * @return a {@link List} of attributes for all methods
     * @author mbrunnli (04.06.2014)
     */
    private List<Map<String, Object>> extractMethods(Class<?> pojo) {

        List<Map<String, Object>> methods = new LinkedList<>();
        for (Method method : pojo.getMethods()) {
            Map<String, Object> methodAttributes = new HashMap<>();
            methodAttributes.put(ModelConstant.NAME, method.getName());
            Map<String, Object> annotations = new HashMap<>();
            extractAnnotationsRecursively(annotations, method.getAnnotations());
            methodAttributes.put(ModelConstant.ANNOTATIONS, annotations);
            methods.add(methodAttributes);
        }
        return methods;
    }

    /**
     * Collect all annotations for the given pojo from setter and getter methods by searching using the
     * attribute names. Annotation information retrieved from the setter and getter methods will be added the
     * the corresponding attribute meta data
     *
     * @param pojo
     *            class for which the setter and getter should be evaluated according to their annotations
     * @param attributes
     *            list of attribute meta data for the generation (object model)
     * @author mbrunnli (01.04.2014)
     */
    private void collectAnnotations(Class<?> pojo, List<Map<String, Object>> attributes) {

        for (Map<String, Object> attr : attributes) {
            Map<String, Object> annotations = new HashMap<>();
            attr.put(ModelConstant.ANNOTATIONS, annotations);
            try {
                Field field = pojo.getField((String) attr.get(ModelConstant.NAME));
                extractAnnotationsRecursively(annotations, field.getAnnotations());
            } catch (NoSuchFieldException e) {
                // Do nothing if the method does not exist
            }
            try {
                Method getter =
                    pojo.getMethod("get" + StringUtils.capitalize((String) attr.get(ModelConstant.NAME)));
                extractAnnotationsRecursively(annotations, getter.getAnnotations());
            } catch (NoSuchMethodException e) {
                // Do nothing if the method does not exist
            }
            try {
                Method getter =
                    pojo.getMethod("is" + StringUtils.capitalize((String) attr.get(ModelConstant.NAME)));
                extractAnnotationsRecursively(annotations, getter.getAnnotations());
            } catch (NoSuchMethodException e) {
                // Do nothing if the method does not exist
            }
            try {
                Method setter =
                    pojo.getMethod("set" + StringUtils.capitalize((String) attr.get(ModelConstant.NAME)));
                extractAnnotationsRecursively(annotations, setter.getAnnotations());
            } catch (NoSuchMethodException e) {
                // Do nothing if the method does not exist
            }
        }
    }

    /**
     * Extracts all information of the given annotations recursively and writes them into the object model
     * (annotationsMap)
     *
     * @param annotationsMap
     *            object model for annotations
     * @param annotations
     *            to be analyzed
     * @author mbrunnli (01.04.2014)
     */
    private void extractAnnotationsRecursively(Map<String, Object> annotationsMap, Annotation[] annotations) {

        for (Annotation annotation : annotations) {
            Map<String, Object> annotationParameters = new HashMap<>();
            annotationsMap.put(annotation.annotationType().getCanonicalName().replaceAll("\\.", "_"),
                annotationParameters);

            for (Method getter : annotation.annotationType().getMethods()) {
                if (getter.getParameterTypes().length > 0 || getter.getName().equals("hashCode")
                    || getter.getName().equals("annotationType") || getter.getName().equals("toString")) {
                    continue;
                }
                try {
                    Object value = getter.invoke(annotation);
                    if (value instanceof Annotation[]) {
                        Map<String, Object> annotationParameterParameters = new HashMap<>();
                        annotationParameters.put(getter.getName(), annotationParameterParameters);
                        extractAnnotationsRecursively(annotationParameterParameters, (Annotation[]) value);
                    } else if (value instanceof Enum<?>[]) {
                        List<String> enumValues = Lists.newLinkedList();
                        for (Enum<?> e : ((Enum<?>[]) value)) {
                            enumValues.add(e.name());
                        }
                        annotationParameters.put(getter.getName(), enumValues);
                    } else if (value instanceof Object[]) {
                        annotationParameters.put(getter.getName(), Arrays.asList(value));
                    } else if (value instanceof Enum<?>) {
                        annotationParameters.put(getter.getName(), ((Enum<?>) value).name());
                    } else {
                        annotationParameters.put(getter.getName(), value);
                    }
                } catch (IllegalAccessException | InvocationTargetException e) {
                    LOG.error("An error occured while retrieving value '{}' from annotation '{}'.",
                        getter.getName(), annotation.getClass(), e);
                }
            }
        }
    }

    /**
     * Determines whether the given attributes behaving as IDs on the persistence layer. The information will
     * be integrated into the default model as stated in {@link #createModel(Class)}
     *
     * @param pojo
     *            {@link Class} object of the POJO the data should be retrieved from
     * @param attributes
     *            a {@link List} of all attributes and their properties
     * @author mbrunnli (12.02.2013)
     */
    private void determinePojoIds(Class<?> pojo, List<Map<String, Object>> attributes) {

        for (Map<String, Object> attr : attributes) {
            try {
                Method getter = null;
                try {
                    getter =
                        pojo.getDeclaredMethod("get"
                            + StringUtil.capFirst((String) attr.get(ModelConstant.NAME)));
                } catch (NoSuchMethodException | SecurityException e) {
                    getter =
                        pojo.getDeclaredMethod("is"
                            + StringUtil.capFirst((String) attr.get(ModelConstant.NAME)));
                }
                if (getter == null) {
                    return;
                }

                Annotation[] annotations = getter.getAnnotations();
                for (Annotation a : annotations) {
                    if ("javax.persistence.Id".equals(a.annotationType().getCanonicalName())) {
                        attr.put("isId", "true");
                        break;
                    }
                }
                if (attr.get("isId") == null) {
                    attr.put("isId", "false");
                }
            } catch (NoSuchMethodException | SecurityException e) {
                // Do nothing, Getter with leading get/is does not exist
            }
        }
    }

}
