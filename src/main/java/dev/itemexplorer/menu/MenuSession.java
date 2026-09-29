package dev.itemexplorer.menu;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ThreadLocalRandom;

/** Distinguishes reopened menus and mount generations, even when inventory revisions match. */
final class MenuSession {
    private static final AtomicLong NEXT = new AtomicLong(ThreadLocalRandom.current().nextLong());
    private MenuSession() {}
    static long next() { return NEXT.incrementAndGet(); }
}
