package org.offeringprotocol.odp.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OdpPaginationTest {
    @Test
    void loadsOnlyOnDemandAndHonorsTheItemLimit() {
        AtomicInteger first = new AtomicInteger();
        List<String> continuations = new ArrayList<>();
        var iterator = OdpPagination.iterate(
                () -> {
                    first.incrementAndGet();
                    return page(List.of("a", "b"), "/next?opaque=1");
                },
                next -> {
                    continuations.add(next);
                    return page(List.of("c", "d"), "/not-fetched");
                },
                3);
        assertEquals(0, first.get());
        assertTrue(iterator.hasNext());
        assertTrue(iterator.hasNext());
        assertEquals(1, first.get());
        assertEquals("a", iterator.next());
        assertEquals("b", iterator.next());
        assertTrue(continuations.isEmpty());
        assertEquals("c", iterator.next());
        assertEquals(List.of("/next?opaque=1"), continuations);
        assertFalse(iterator.hasNext());
        assertThrows(NoSuchElementException.class, iterator::next);
        assertThrows(UnsupportedOperationException.class, iterator::remove);
    }

    @Test
    void skipsEmptyPagesAndStopsAtTheEnd() {
        AtomicInteger calls = new AtomicInteger();
        var iterator = OdpPagination.iterate(
                () -> page(List.<String>of(), "/1"),
                next -> {
                    calls.incrementAndGet();
                    return next.equals("/1") ? page(List.of(), "/2") : page(List.of("a"), null);
                },
                Long.MAX_VALUE);
        assertEquals("a", iterator.next());
        assertFalse(iterator.hasNext());
        assertFalse(iterator.hasNext());
        assertEquals(2, calls.get());
    }

    @Test
    void preservesDeliveredItemsAndNeverResumesAfterFailure() {
        AtomicInteger calls = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("failed page");
        var iterator = OdpPagination.iterate(
                () -> page(List.of("a"), "/next"),
                next -> {
                    calls.incrementAndGet();
                    throw failure;
                },
                10);
        List<String> delivered = new ArrayList<>();
        delivered.add(iterator.next());
        var reported = assertThrows(IllegalStateException.class, iterator::hasNext);
        assertSame(failure, reported.getCause());
        assertSame(reported, assertThrows(IllegalStateException.class, iterator::next));
        assertEquals(List.of("a"), delivered);
        assertEquals(1, calls.get());
    }

    @Test
    void preservesTypedLimitFailuresWithoutWrappingOrResuming() {
        AtomicInteger calls = new AtomicInteger();
        var failure = new OdpResponseLimitException("Response exceeds its byte limit");
        var iterator = OdpPagination.iterate(
                () -> page(List.of("a"), "/next"),
                next -> {
                    calls.incrementAndGet();
                    throw failure;
                },
                10);
        assertEquals("a", iterator.next());
        assertSame(failure, assertThrows(OdpResponseLimitException.class, iterator::hasNext));
        assertSame(failure, assertThrows(OdpResponseLimitException.class, iterator::next));
        assertEquals("RESPONSE_LIMIT_EXCEEDED", failure.code());
        assertFalse(failure.retryable());
        assertEquals(1, calls.get());
    }

    @Test
    void refusesLoopsAndExcessivePagesWithoutFetchingAgain() {
        AtomicInteger loops = new AtomicInteger();
        var looping = OdpPagination.iterate(
                () -> page(List.<String>of(), "/same"),
                next -> {
                    loops.incrementAndGet();
                    return page(List.of(), "/same");
                },
                100);
        assertThrows(IllegalStateException.class, looping::hasNext);
        assertEquals(1, loops.get());
        AtomicInteger pages = new AtomicInteger(1);
        var bounded = OdpPagination.iterate(
                () -> page(List.<String>of(), "/1"), next -> page(List.of(), "/" + pages.incrementAndGet()), 100);
        assertThrows(IllegalStateException.class, bounded::hasNext);
        assertEquals(OdpPagination.MAXIMUM_PAGES, pages.get());
    }

    @Test
    void searchPagesAdaptWithoutLosingTheOriginalRefinements() {
        var groups = List.of(new OfferingPage.RefinementGroup(
                "color", List.of(new OfferingPage.RefinementBucket(OdpJson.parseTree("\"black\""), 1, "exact"))));
        var search = new OfferingPage(true, Odp.VERSION, List.of(), "/next", groups, Map.of());
        var page = search.asPage();
        assertEquals(search.items(), page.items());
        assertEquals(search.next(), page.next());
        assertEquals(search.odpVersion(), page.odpVersion());
        assertEquals(search.authExpands(), page.authExpands());
        assertEquals(search.additional(), page.additional());
        assertEquals(groups, search.refinements());
    }

    @Test
    void zeroLimitDoesNotLoadAndInvalidInputsFailLocally() {
        var empty = OdpPagination.iterate(() -> fail("must not fetch"), next -> fail("must not fetch"), 0);
        assertFalse(empty.hasNext());
        assertThrows(NoSuchElementException.class, empty::next);
        assertThrows(
                IllegalArgumentException.class,
                () -> OdpPagination.iterate(() -> page(List.of(), null), next -> page(List.of(), null), -1));
        var invalid = OdpPagination.iterate(() -> null, next -> null, 1);
        var failure = assertThrows(IllegalStateException.class, invalid::hasNext);
        assertInstanceOf(NullPointerException.class, failure.getCause());
        assertSame(failure, assertThrows(IllegalStateException.class, invalid::hasNext));
    }

    private static <T> Page<T> page(List<T> items, String next) {
        return new Page<>(null, Odp.VERSION, items, next, Map.of());
    }
}
