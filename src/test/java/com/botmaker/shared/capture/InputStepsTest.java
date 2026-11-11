package com.botmaker.shared.capture;

import com.botmaker.shared.steps.DeviceSteps;
import com.botmaker.shared.steps.DeviceSteps.Step;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InputStepsTest {

    private final List<String> done = new ArrayList<>();
    private final List<Step> steps = new CopyOnWriteArrayList<>();

    /** A controller that only notes what it was asked; a click is its own move and presses. */
    private final NativeController fake = (NativeController) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{NativeController.class}, (self, method, args) -> {
                if (method.isDefault()) return InvocationHandler.invokeDefault(self, method, args);
                done.add(method.getName());
                return null;
            });

    @Test
    void aClickIsOneStepAndAMoveNone() {
        NativeController controller = InputSteps.around(fake);
        DeviceSteps.recording(recorder(), () -> {
            controller.click(450, 1520, 1);
            controller.mouseMove(1, 2);
            controller.typeText("hunter2");
            return null;
        });

        assertEquals(List.of("click 450 1520 1", "typeText (7 characters)"),
                steps.stream().map(Step::command).toList());
        assertEquals(InputSteps.DEVICE, steps.getFirst().device());
        assertEquals(List.of("mouseMove", "mouseButton", "mouseButton", "mouseMove", "typeText"), done);
    }

    @Test
    void theFactoryTellsATestControllersInput() {
        try {
            NativeControllerFactory.setForTesting(fake);
            DeviceSteps.recording(recorder(), () -> {
                NativeControllerFactory.get().scroll(-3);
                return null;
            });
        } finally {
            NativeControllerFactory.setForTesting(null);
        }

        assertEquals(List.of("scroll -3"), steps.stream().map(Step::command).toList());
    }

    private DeviceSteps.Recorder recorder() {
        return new DeviceSteps.Recorder() {
            @Override
            public void step(Step step) {
                steps.add(step);
            }

            @Override
            public void progress(String device, String command, long done, long total) {
            }
        };
    }
}
