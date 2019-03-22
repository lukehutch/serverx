package serverx.utils;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.HashSet;
import java.util.logging.Level;

import serverx.server.ServerxVerticle;
import serverx.template.TemplateModel;

/**
 * ReflectionUtils.
 */
public class ReflectionUtils {
    /**
     * Gets the field name to method handle.
     *
     * @param cls
     *            the cls
     * @return the field name to method handle
     */
    public static HashMap<String, MethodHandle> getFieldNameToMethodHandle(final Class<?> cls) {
        final var fieldNameToMethodHandle = new HashMap<String, MethodHandle>();
        final var inaccessibleProperties = new HashSet<String>();
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            // Look for getter methods before fields
            for (final Method m : c.getDeclaredMethods()) {
                // Skip static methods
                if ((m.getModifiers() & Modifier.STATIC) != 0) {
                    continue;
                }
                // Look for getter methods
                String propName = null;
                if (m.getParameterCount() == 0) {
                    final var methodName = m.getName();
                    if (methodName.startsWith("get") && methodName.length() > 3) {
                        propName = Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);
                    } else if (methodName.startsWith("is") && methodName.length() > 2) {
                        propName = Character.toLowerCase(methodName.charAt(2)) + methodName.substring(1);
                    }
                }
                // Ignore non-getter methods
                if (propName == null) {
                    continue;
                }
                // Don't override properties in subclasses with properties in superclasses 
                if (fieldNameToMethodHandle.containsKey(propName)) {
                    continue;
                }
                // Make method public if necessary
                if (((m.getModifiers() & Modifier.PUBLIC) == 0)
                        && ServerxVerticle.serverProperties.enableNonPublicDataModelFields) {
                    try {
                        m.setAccessible(true);
                    } catch (InaccessibleObjectException | SecurityException e) {
                        // Ignore -- the unreflect() call will probably fail though
                    }
                }
                // Unreflect method
                try {
                    final var methodHandle = MethodHandles.lookup().unreflect(m);
                    fieldNameToMethodHandle.put(propName, methodHandle);
                } catch (final IllegalAccessException e) {
                    // Record that property could not be accessed
                    inaccessibleProperties.add(propName);
                }
            }
            // Look for fields, only using them if a corresponding getter was not found
            for (final Field f : c.getDeclaredFields()) {
                // Skip static fields
                if ((f.getModifiers() & Modifier.STATIC) != 0) {
                    continue;
                }
                // Don't override properties in subclasses with properties in superclasses,
                // or properties found in getter names with properties found in field names
                final var fieldName = f.getName();
                if (fieldNameToMethodHandle.containsKey(fieldName)) {
                    continue;
                }
                // Make field public if necessary
                if (((f.getModifiers() & Modifier.PUBLIC) == 0)
                        && ServerxVerticle.serverProperties.enableNonPublicDataModelFields) {
                    try {
                        f.setAccessible(true);
                    } catch (InaccessibleObjectException | SecurityException e) {
                        // Ignore -- the unreflect() call will probably fail though
                    }
                }
                // Unreflect field
                try {
                    final var methodHandle = MethodHandles.lookup().unreflectGetter(f);
                    fieldNameToMethodHandle.put(f.getName(), methodHandle);
                    // Since field was found, remove reference to any failed getter
                    inaccessibleProperties.remove(fieldName);
                } catch (final IllegalAccessException e) {
                    // Record that field could not be accessed
                    inaccessibleProperties.add(fieldName);
                }
            }
        }
        final var isTemplate = TemplateModel.class.isAssignableFrom(cls);
        ServerxVerticle.logger.log(Level.INFO,
                "Properties of " + (isTemplate ? TemplateModel.class.getSimpleName() : "class") + " "
                        + cls.getName() + ": " + String.join(", ", fieldNameToMethodHandle.keySet()));
        if (!inaccessibleProperties.isEmpty()) {
            ServerxVerticle.logger.log(Level.WARNING,
                    "Inaccessible properties of " + (isTemplate ? TemplateModel.class.getSimpleName() : "class")
                            + " " + cls.getName() + ": " + String.join(", ", inaccessibleProperties));
        }
        return fieldNameToMethodHandle;
    }

}
