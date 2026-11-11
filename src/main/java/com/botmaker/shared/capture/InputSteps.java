package com.botmaker.shared.capture;

import com.botmaker.shared.steps.DeviceSteps;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A {@link NativeController} whose input is told to {@link DeviceSteps} as window steps: a click, a key, typed text, a
 * scroll, a window focused. Only the calls a caller makes count, not the moves and presses a click is made of, and a
 * pointer's move alone is not a step. Everything else goes straight through.
 */
final class InputSteps implements InvocationHandler {

    static final String DEVICE = "window";
    /** A press and its release are a step each, so a held key or button shows as held. */
    private static final Set<String> STEPS = Set.of("postLeftClick", "click", "clickRestoringCursor", "keyDown",
            "keyUp", "typeText", "mouseButton", "scroll", "focusWindow");

    private final NativeController controller;

    private InputSteps(NativeController controller) {
        this.controller = controller;
    }

    static NativeController around(NativeController controller) {
        return (NativeController) Proxy.newProxyInstance(NativeController.class.getClassLoader(),
                new Class<?>[]{NativeController.class}, new InputSteps(controller));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                default -> "InputSteps(" + controller + ")";
            };
        }
        if (!STEPS.contains(method.getName())) return call(method, args);
        return DeviceSteps.step(DEVICE, said(method, args), () -> {
            try {
                return call(method, args);
            } catch (Exception | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        }, result -> "");
    }

    private Object call(Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(controller, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /**
     * {@code click 450 1520 1}, a window by its title; typed text by its length only, since it may be a password a
     * bot types into a game.
     */
    static String said(Method method, Object[] args) {
        if (args == null || args.length == 0) return method.getName();
        return method.getName() + " " + Arrays.stream(args).map(arg -> switch (arg) {
            case GenericWindow window -> "\"" + window.getTitle() + "\"";
            case String text -> "(" + text.length() + " characters)";
            case null -> "null";
            default -> String.valueOf(arg);
        }).collect(Collectors.joining(" "));
    }
}
