package org.offeringprotocol.odp.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Bounded helpers for following opaque ODP continuation references. */
public final class OdpPagination {
    public static final int MAXIMUM_PAGES = 16;

    private OdpPagination() {}

    public static <T> List<Page<T>> pages(Page<T> first, PageLoader<T> loader) {
        List<Page<T>> pages = new ArrayList<>();
        Set<String> continuations = new HashSet<>();
        Page<T> page = first;
        for (int count = 0; count < MAXIMUM_PAGES; count++) {
            pages.add(page);
            if (page.next() == null) {
                return List.copyOf(pages);
            }
            if (!continuations.add(page.next())) {
                throw new IllegalStateException("ODP pagination loop detected");
            }
            if (count == MAXIMUM_PAGES - 1) {
                throw new IllegalStateException("ODP pagination exceeded the 16-page traversal limit");
            }
            page = loader.load(page.next());
        }
        throw new IllegalStateException("ODP pagination exceeded the 16-page traversal limit");
    }

    public static <T> List<T> items(Page<T> first, PageLoader<T> loader) {
        return pages(first, loader).stream()
                .flatMap(page -> page.items().stream())
                .toList();
    }

    /**
     * A single-use, synchronous iterator. Requests occur in hasNext or next, never in advance.
     * The caller's item limit is independent of the Service page size. Not thread-safe.
     */
    public static <T> Iterator<T> iterate(Supplier<Page<T>> first, PageLoader<T> loader, long maximumItems) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(loader, "loader");
        if (maximumItems < 0) {
            throw new IllegalArgumentException("The item limit must not be negative");
        }
        return new ItemIterator<>(first, loader, maximumItems);
    }

    private static final class ItemIterator<T> implements Iterator<T> {
        private final Supplier<Page<T>> first;
        private final PageLoader<T> loader;
        private final long maximumItems;
        private final Set<String> continuations = new HashSet<>();
        private Page<T> page;
        private Iterator<T> current = Collections.emptyIterator();
        private int pageCount;
        private long emitted;
        private IllegalStateException failure;

        private ItemIterator(Supplier<Page<T>> first, PageLoader<T> loader, long maximumItems) {
            this.first = first;
            this.loader = loader;
            this.maximumItems = maximumItems;
        }

        @Override
        public boolean hasNext() {
            if (failure != null) {
                throw failure;
            }
            if (emitted == maximumItems) {
                return false;
            }
            try {
                while (!current.hasNext()) {
                    if (page != null && page.next() == null) {
                        return false;
                    }
                    if (pageCount == MAXIMUM_PAGES) {
                        throw new IllegalStateException("ODP pagination exceeded the 16-page traversal limit");
                    }
                    if (page != null && !continuations.add(page.next())) {
                        throw new IllegalStateException("ODP pagination loop detected");
                    }
                    page = Objects.requireNonNull(page == null ? first.get() : loader.load(page.next()), "page");
                    pageCount++;
                    current = page.items().iterator();
                }
                return true;
            } catch (OdpResponseLimitException exception) {
                failure = exception;
                throw exception;
            } catch (RuntimeException exception) {
                IllegalStateException terminal = new IllegalStateException("ODP pagination failed", exception);
                failure = terminal;
                throw terminal;
            }
        }

        @Override
        public T next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            emitted++;
            return current.next();
        }
    }

    @FunctionalInterface
    public interface PageLoader<T> {
        Page<T> load(String continuation);
    }
}
