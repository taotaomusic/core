package io.autopatch.testhost;

/**
 * 测试用的「宿主类」。方法体扮演 head（修好之后）的实现，
 * 移植器把它搬进补丁静态方法后，行为应当与直接调用它一致。
 */
public class Calc {
    public int base;

    public Calc(int base) {
        this.base = base;
    }

    /** 实例方法 + 基本类型参数/返回：验证 receiver 强转、arg 拆箱、返回装箱。 */
    public int add(int x) {
        return base + x + 1;
    }

    /** 静态方法 + 引用类型：验证 receiver=null 与引用返回。 */
    public static String tag(String s) {
        return "tag:" + s;
    }
}
