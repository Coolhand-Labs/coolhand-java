package com.coolhandlabs.coolhand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.coolhandlabs.coolhand.internal.Json;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DeclaredContextTest {
    private static DeclaredContext ctx(String reporter, DeclaredWorkload w, DeclaredTemplate t) {
        return new DeclaredContext(reporter, w, t);
    }

    private static String json(Optional<DeclaredContext> c) {
        return c.map(Json::stringify).orElse("none");
    }

    @Test
    void noDeclarationOutsideABlock() {
        assertTrue(Coolhand.currentContext().isEmpty());
    }

    @Test
    void declaresReporterWorkloadAndTemplateAndReturnsTheResult() {
        String seen = Coolhand.withContext(
                ctx("squishling", DeclaredWorkload.ofKey("Parser#call").withName("Parser#call"),
                        DeclaredTemplate.ofKey("Parser#call/squish:L2")),
                () -> json(Coolhand.currentContext()));
        assertEquals("{\"reporter\":\"squishling\",\"workload\":{\"key\":\"Parser#call\",\"name\":\"Parser#call\"},"
                + "\"template\":{\"key\":\"Parser#call/squish:L2\"}}", seen);
        assertTrue(Coolhand.currentContext().isEmpty());
    }

    @Test
    void builderShorthandsProduceTheSameShape() {
        DeclaredContext built = DeclaredContext.builder().reporter("r").workload("w", "W", "desc").template("t", "T").build();
        assertEquals("{\"reporter\":\"r\",\"workload\":{\"key\":\"w\",\"name\":\"W\",\"description\":\"desc\"},"
                + "\"template\":{\"key\":\"t\",\"name\":\"T\"}}", Json.stringify(built));
    }

    @Test
    void innermostValueWinsPerField() {
        String seen = Coolhand.withContext(ctx("a", DeclaredWorkload.ofKey("w1"), null),
                () -> Coolhand.withContext(ctx("b", null, null), () -> json(Coolhand.currentContext())));
        assertEquals("{\"reporter\":\"b\",\"workload\":{\"key\":\"w1\"}}", seen);
    }

    @Test
    void workloadAndTemplateAreReplacedWholesale() {
        String seen = Coolhand.withContext(ctx(null, new DeclaredWorkload(null, "w1", "One", "d"), null),
                () -> Coolhand.withContext(ctx(null, DeclaredWorkload.ofId("abc"), null),
                        () -> json(Coolhand.currentContext())));
        assertEquals("{\"workload\":{\"id\":\"abc\"}}", seen);
    }

    @Test
    void aNewWorkloadDoesNotInheritTheOuterTemplate() {
        String seen = Coolhand.withContext(ctx(null, DeclaredWorkload.ofKey("w1"), DeclaredTemplate.ofKey("t1")),
                () -> Coolhand.withContext(ctx(null, DeclaredWorkload.ofKey("w2"), null),
                        () -> json(Coolhand.currentContext())));
        assertEquals("{\"workload\":{\"key\":\"w2\"}}", seen);
    }

    @Test
    void aTemplateAloneInheritsTheOuterWorkload() {
        String seen = Coolhand.withContext(ctx(null, DeclaredWorkload.ofKey("w1"), DeclaredTemplate.ofKey("t1")),
                () -> Coolhand.withContext(ctx(null, null, DeclaredTemplate.ofKey("t2")),
                        () -> json(Coolhand.currentContext())));
        assertEquals("{\"workload\":{\"key\":\"w1\"},\"template\":{\"key\":\"t2\"}}", seen);
    }

    @Test
    void restoresTheOuterContextAfterAnException() {
        Coolhand.withContext(ctx("outer", null, null), () -> {
            assertThrows(IllegalStateException.class, () -> Coolhand.withContext(ctx("inner", null, null), () -> {
                throw new IllegalStateException("boom");
            }));
            assertEquals("outer", Coolhand.currentContext().orElseThrow().reporter());
        });
        assertTrue(Coolhand.currentContext().isEmpty());
    }

    @Test
    void propagatesCheckedExceptionsUnchanged() {
        IOException thrown = new IOException("io");
        IOException caught = assertThrows(IOException.class, () -> Coolhand.withContext(ctx("r", null, null), () -> {
            if (thrown != null) {
                throw thrown;
            }
            return 1;
        }));
        assertSame(thrown, caught);
    }

    @Test
    void runnableOverloadRunsTheBlock() {
        AtomicReference<String> seen = new AtomicReference<>();
        Coolhand.withContext(ctx("r", null, null), () -> seen.set(Coolhand.currentContext().orElseThrow().reporter()));
        assertEquals("r", seen.get());
    }

    @Test
    void anEmptyDeclarationDeclaresNothing() {
        assertTrue(Coolhand.withContext(ctx(null, null, null), Coolhand::currentContext).isEmpty());
        assertTrue(Coolhand.withContext(ctx("   ", null, null), Coolhand::currentContext).isEmpty());
    }

    @Test
    void validatesBeforeTheBlockRuns() {
        AtomicReference<Boolean> ran = new AtomicReference<>(false);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Coolhand.withContext(ctx(null, DeclaredWorkload.ofKey(" "), null), () -> ran.set(true)));
        assertEquals("withContext: workload.key must not be blank", e.getMessage());
        assertEquals(false, ran.get());
    }

    @Test
    void rejectsInvalidKeysAndDescriptions() {
        assertEquals("withContext: workload.key must not start or end with \"/\"", message(DeclaredWorkload.ofKey("/a"), null));
        assertEquals("withContext: workload.key must not contain \"//\"", message(DeclaredWorkload.ofKey("a//b"), null));
        assertEquals("withContext: workload.key must not have leading or trailing whitespace",
                message(DeclaredWorkload.ofKey(" a"), null));
        assertEquals("withContext: workload.key must not contain control or invisible characters",
                message(DeclaredWorkload.ofKey("a​b"), null));
        assertEquals("withContext: workload.key must be valid Unicode (contains a lone surrogate)",
                message(DeclaredWorkload.ofKey("a\uD800b"), null));
        assertEquals("withContext: workload.key must be at most 255 characters",
                message(DeclaredWorkload.ofKey("k".repeat(256)), null));
        assertEquals("withContext: workload.description must be at most 1000 characters",
                message(DeclaredWorkload.ofKey("k").withDescription("d".repeat(1001)), null));
        assertEquals("withContext: workload needs a non-blank \"id\" or a \"key\"",
                message(new DeclaredWorkload(" ", null, "n", null), null));
        assertEquals("withContext: template.key needs a workload in this or an enclosing withContext (or use template.id)",
                message(null, DeclaredTemplate.ofKey("t")));
        // 255 code points of an astral character are allowed (length is counted in code points)
        Coolhand.withContext(ctx(null, DeclaredWorkload.ofKey("😀".repeat(255)), null), () -> {});
    }

    @Test
    void aTemplateIdNeedsNoWorkload() {
        assertEquals("{\"template\":{\"id\":\"tid\"}}",
                Coolhand.withContext(ctx(null, null, DeclaredTemplate.ofId("tid")), () -> json(Coolhand.currentContext())));
    }

    private static String message(DeclaredWorkload w, DeclaredTemplate t) {
        return assertThrows(IllegalArgumentException.class, () -> Coolhand.withContext(ctx(null, w, t), () -> {})).getMessage();
    }

    @Test
    void wrapWithContextCarriesTheContextToAnotherThread() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Callable<Optional<DeclaredContext>> task = Coolhand.withContext(ctx("r", null, null),
                    () -> Coolhand.wrapWithContext(Coolhand::currentContext));
            assertEquals("r", pool.submit(task).get().orElseThrow().reporter());
            assertTrue(pool.submit(Coolhand::currentContext).get().isEmpty());
        } finally {
            pool.shutdownNow();
        }
    }
}
