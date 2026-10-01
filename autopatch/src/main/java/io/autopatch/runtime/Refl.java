package io.autopatch.runtime;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 补丁运行时的反射工具。
 *
 * 移植进补丁类的方法体里，对**宿主包成员**的 getfield/putfield/invoke 会被改写成调用
 * 这里的静态方法 —— 因为补丁类与宿主类是两套 Class，直连访问宿主的私有成员会抛
 * {@code IllegalAccessError}。走反射 + setAccessible 绕开访问控制。
 *
 * 这是一个**自包含**的类（不引用任何宿主类型），会被单独打进补丁 DEX 一起下发。
 * 类型解析用本类的类加载器：设备上补丁类加载器以宿主为父，因此能 load 到宿主类。
 */
public final class Refl {
    private Refl() {}

    /** 读字段（含私有），沿继承链向上找。 */
    public static Object getField(Object target, String ownerInternalName, String name) {
        try {
            Field f = findField(target.getClass(), name);
            f.setAccessible(true);
            return f.get(target);
        } catch (Exception e) {
            throw new RuntimeException("热修反射读字段失败：" + ownerInternalName + "." + name, e);
        }
    }

    /** 写字段（含私有）。 */
    public static void setField(Object target, String ownerInternalName, String name, Object value) {
        try {
            Field f = findField(target.getClass(), name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("热修反射写字段失败：" + ownerInternalName + "." + name, e);
        }
    }

    /** 调用实例方法（含私有），按参数描述符精确匹配重载。 */
    public static Object invoke(Object target, String ownerInternalName, String name,
                                String[] paramDescs, Object[] args) {
        try {
            ClassLoader cl = target.getClass().getClassLoader();
            Class<?>[] types = resolveTypes(paramDescs, cl);
            Method m = findMethod(target.getClass(), name, types);
            m.setAccessible(true);
            return m.invoke(target, args);
        } catch (Exception e) {
            throw new RuntimeException("热修反射调用失败：" + ownerInternalName + "." + name, e);
        }
    }

    /** 调用静态方法（含私有）。owner 用本类加载器解析（父链含宿主）。 */
    public static Object invokeStatic(String ownerInternalName, String name,
                                      String[] paramDescs, Object[] args) {
        try {
            ClassLoader cl = Refl.class.getClassLoader();
            Class<?> owner = cl.loadClass(ownerInternalName.replace('/', '.'));
            Class<?>[] types = resolveTypes(paramDescs, cl);
            Method m = findMethod(owner, name, types);
            m.setAccessible(true);
            return m.invoke(null, args);
        } catch (Exception e) {
            throw new RuntimeException("热修反射静态调用失败：" + ownerInternalName + "." + name, e);
        }
    }

    /** 构造宿主实例（含私有构造器）。 */
    public static Object construct(String ownerInternalName, String[] paramDescs, Object[] args) {
        try {
            ClassLoader cl = Refl.class.getClassLoader();
            Class<?> owner = cl.loadClass(ownerInternalName.replace('/', '.'));
            Class<?>[] types = resolveTypes(paramDescs, cl);
            Constructor<?> c = owner.getDeclaredConstructor(types);
            c.setAccessible(true);
            return c.newInstance(args);
        } catch (Exception e) {
            throw new RuntimeException("热修反射构造失败：" + ownerInternalName, e);
        }
    }

    private static Field findField(Class<?> from, String name) throws NoSuchFieldException {
        for (Class<?> c = from; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // 继续往父类找
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Method findMethod(Class<?> from, String name, Class<?>[] types) throws NoSuchMethodException {
        for (Class<?> c = from; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name, types);
            } catch (NoSuchMethodException ignored) {
                // 继续往父类找
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static Class<?>[] resolveTypes(String[] descs, ClassLoader cl) throws ClassNotFoundException {
        Class<?>[] types = new Class<?>[descs.length];
        for (int i = 0; i < descs.length; i++) {
            types[i] = resolveType(descs[i], cl);
        }
        return types;
    }

    /** JVM 类型描述符 → Class：基本类型、引用类型、数组都支持。 */
    private static Class<?> resolveType(String desc, ClassLoader cl) throws ClassNotFoundException {
        switch (desc.charAt(0)) {
            case 'Z': return boolean.class;
            case 'B': return byte.class;
            case 'C': return char.class;
            case 'S': return short.class;
            case 'I': return int.class;
            case 'J': return long.class;
            case 'F': return float.class;
            case 'D': return double.class;
            case 'V': return void.class;
            case 'L': return cl.loadClass(desc.substring(1, desc.length() - 1).replace('/', '.'));
            case '[': return Class.forName(desc.replace('/', '.'), false, cl);
            default: throw new ClassNotFoundException(desc);
        }
    }
}
