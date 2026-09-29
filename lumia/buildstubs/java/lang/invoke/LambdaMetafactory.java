package java.lang.invoke;

/**
 * Compile-time stub only. android.jar lacks LambdaMetafactory, so javac can't emit lambdas against it;
 * d8 desugars the resulting invokedynamic into plain classes, so this never ships in the APK.
 */
public final class LambdaMetafactory {
    private LambdaMetafactory() {}

    public static CallSite metafactory(MethodHandles.Lookup caller, String name, MethodType type,
                                       MethodType samType, MethodHandle impl, MethodType instantiated) {
        throw new UnsupportedOperationException();
    }

    public static CallSite altMetafactory(MethodHandles.Lookup caller, String name, MethodType type, Object... args) {
        throw new UnsupportedOperationException();
    }
}
