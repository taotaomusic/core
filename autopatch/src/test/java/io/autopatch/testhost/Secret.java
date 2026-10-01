package io.autopatch.testhost;

/**
 * 私有成员访问的测试宿主。
 *
 * {@link #compute(int)} 读私有字段 {@code seed} + 调私有方法 {@code salt} —— 移植进补丁类后，
 * 若直连访问会抛 IllegalAccessError；改写成反射才能跑通。
 */
public class Secret {
    private int seed;

    public Secret(int seed) {
        this.seed = seed;
    }

    private int salt(int x) {
        return x * 3;
    }

    public int compute(int x) {
        return salt(x) + seed;
    }
}
