package io.autopatch.testhost;

/** 测试用的运行时接口，形状与宿主 com.taotao.music.hotfix.PatchDispatcher 一致。 */
public interface PatchDispatcher {
    boolean isSupport(String methodKey);

    Object dispatch(String methodKey, Object receiver, Object[] args);
}
