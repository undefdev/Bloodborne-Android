package com.ducvd89.bloodborne;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Runs every public static no-argument void method (other than main) of the named test classes.
 * Exits with 1 if any test fails.
 */
public final class TestRunner {
    public static void main(String[] args) throws Exception {
        int passed = 0, failed = 0;
        for (String name : args) {
            Class<?> c = Class.forName(name);
            Method[] methods = c.getDeclaredMethods();
            Arrays.sort(methods, Comparator.comparing(Method::getName));
            for (Method m : methods) {
                int mod = m.getModifiers();
                if (!Modifier.isPublic(mod) || !Modifier.isStatic(mod) || m.getParameterCount() != 0
                    || m.getReturnType() != void.class || m.getName().equals("main")) continue;
                String label = c.getSimpleName() + "." + m.getName();
                try {
                    m.invoke(null);
                    System.out.println("PASS " + label);
                    passed++;
                } catch (InvocationTargetException e) {
                    System.out.println("FAIL " + label + ": " + e.getCause());
                    e.getCause().printStackTrace(System.out);
                    failed++;
                }
            }
        }
        System.out.println(passed + " passed, " + failed + " failed");
        System.exit(failed == 0 && passed > 0 ? 0 : 1);
    }
}
