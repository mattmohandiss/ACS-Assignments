package com.acertainbookstore.client.tests;

import static org.junit.Assert.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.acertainbookstore.business.BookCopy;
import com.acertainbookstore.business.ImmutableStockBook;
import com.acertainbookstore.business.SingleLockConcurrentCertainBookStore;
import com.acertainbookstore.business.StockBook;
import com.acertainbookstore.business.TwoLevelLockingConcurrentCertainBookStore;
import com.acertainbookstore.interfaces.BookStore;
import com.acertainbookstore.interfaces.StockManager;
import com.acertainbookstore.utils.BookStoreConstants;
import com.acertainbookstore.utils.BookStoreException;

public class ConcurrentBookStoreTest {

    private static boolean singleLock = true;
    private static StockManager storeManager;
    private static BookStore client;

    @BeforeClass
    public static void setUpBeforeClass() {
        try {
            String singleLockProperty = System.getProperty(BookStoreConstants.PROPERTY_KEY_SINGLE_LOCK);
            singleLock = (singleLockProperty != null) ? Boolean.parseBoolean(singleLockProperty) : singleLock;

            if (singleLock) {
                SingleLockConcurrentCertainBookStore store = new SingleLockConcurrentCertainBookStore();
                storeManager = store;
                client = store;
            } else {
                TwoLevelLockingConcurrentCertainBookStore store = new TwoLevelLockingConcurrentCertainBookStore();
                storeManager = store;
                client = store;
            }

            storeManager.removeAllBooks();
        } catch (Exception e) {
            e.printStackTrace();
            fail("failed to set up test");
        }
    }

    @Before
    public void clearStoreBeforeEach() throws BookStoreException {
        storeManager.removeAllBooks();
    }

    @After
    public void clearStoreAfterEach() throws BookStoreException {
        storeManager.removeAllBooks();
    }

    @AfterClass
    public static void tearDownAfterClass() throws BookStoreException {
        storeManager.removeAllBooks();
    }

    /**
     * addBooks helper
     */
    private void addBooks(int[] isbns, int initialCopies) throws BookStoreException {
        Set<StockBook> booksToAdd = new HashSet<StockBook>();
        for (int i = 0; i < isbns.length; i++) {
            booksToAdd.add(new ImmutableStockBook(
                    isbns[i],
                    "Concurrent Book " + i,
                    "Test Author",
                    10.0f,
                    initialCopies,
                    0, 0, 0,
                    false));
        }
        storeManager.addBooks(booksToAdd);
    }

    /**
     * oneCopyOfEach helper
     */
    private Set<BookCopy> oneCopyOfEach(int[] isbns) {
        Set<BookCopy> copies = new HashSet<BookCopy>();
        for (int i = 0; i < isbns.length; i++) {
            copies.add(new BookCopy(isbns[i], 1));
        }
        return copies;
    }

    /**
     * getStockFor helper
     */
    private List<StockBook> getStockFor(int[] isbns) throws BookStoreException {
        Set<Integer> isbnSet = new HashSet<Integer>();
        for (int i = 0; i < isbns.length; i++) {
            isbnSet.add(isbns[i]);
        }
        return storeManager.getBooksByISBN(isbnSet);
    }

    /**
     * test 1:
     * one thread buys 1 copy of each book,
     * another thread adds 1 copy of each book.
     * they both operate on the same set of books.
     */
    @Test
    public void testBuyAndAddCopiesInParallel() throws Exception {
        final int[] isbns = new int[] { 1001, 1002, 1003 };
        final int initialCopies = 100;
        final int iterations = 100;

        addBooks(isbns, initialCopies);
        final Set<BookCopy> oneOfEach = oneCopyOfEach(isbns);

        final boolean[] failed = new boolean[] { false };
        final String[] errorMsg = new String[] { null };

        Runnable buyer = new Runnable() {
            public void run() {
                try {
                    for (int i = 0; i < iterations; i++) {
                        client.buyBooks(oneOfEach);
                    }
                } catch (BookStoreException e) {
                    failed[0] = true;
                    errorMsg[0] = "Buyer thread failed: " + e.getMessage();
                }
            }
        };

        Runnable adder = new Runnable() {
            public void run() {
                try {
                    for (int i = 0; i < iterations; i++) {
                        storeManager.addCopies(oneOfEach);
                    }
                } catch (BookStoreException e) {
                    failed[0] = true;
                    errorMsg[0] = "Adder thread failed: " + e.getMessage();
                }
            }
        };

        Thread t1 = new Thread(buyer, "buy-thread");
        Thread t2 = new Thread(adder, "add-thread");

        t1.start();
        t2.start();

        t1.join();
        t2.join();

        if (failed[0]) {
            fail(errorMsg[0]);
        }

        List<StockBook> finalStock = getStockFor(isbns);
        assertEquals(isbns.length, finalStock.size());

        for (StockBook book : finalStock) {
            assertEquals("Final copies mismatch for ISBN " + book.getISBN(),
                    initialCopies, book.getNumCopies());
        }
    }

    /**
     * test 2:
     * thread 1 writes:
     *  - buys 1 copy
     *  - then adds 1 copy
     *
     * thread 2 reads:
     *  - getBooksByISBN
     */
    @Test
    public void testReadersSeeConsistentTrilogy() throws Exception {
        final int[] isbns = new int[] { 2001, 2002, 2003 };
        final int baseCopies = 20;
        final int writerIterations = 2000;
        final int readerSnapshots = 5000;

        addBooks(isbns, baseCopies);
        final Set<BookCopy> oneOfEach = oneCopyOfEach(isbns);

        final boolean[] failed = new boolean[] { false };
        final String[] errorMsg = new String[] { null };
        final boolean[] running = new boolean[] { true };

        Runnable writer = new Runnable() {
            public void run() {
                try {
                    for (int i = 0; i < writerIterations && running[0]; i++) {
                        client.buyBooks(oneOfEach);
                        storeManager.addCopies(oneOfEach);
                    }
                } catch (BookStoreException e) {
                    failed[0] = true;
                    errorMsg[0] = "writer thread failed: " + e.getMessage();
                }
            }
        };

        Runnable reader = new Runnable() {
            public void run() {
                try {
                    for (int i = 0; i < readerSnapshots && !failed[0]; i++) {
                        List<StockBook> snapshot = getStockFor(isbns);

                        if (snapshot.size() != isbns.length) {
                            failed[0] = true;
                            errorMsg[0] = "unexpected size: " + snapshot.size();
                            return;
                        }

                        int seenCopies = -1;
                        for (StockBook book : snapshot) {
                            int copies = book.getNumCopies();
                            if (seenCopies == -1) {
                                seenCopies = copies;
                            } else if (copies != seenCopies) {
                                failed[0] = true;
                                errorMsg[0] = "inconsistent snapshot";
                                return;
                            }
                        }
                    }
                } catch (BookStoreException e) {
                    failed[0] = true;
                    errorMsg[0] = "reader thread failed: " + e.getMessage();
                } finally {
                    running[0] = false;
                }
            }
        };

        Thread tWriter = new Thread(writer, "writer-thread");
        Thread tReader = new Thread(reader, "reader-thread");

        tWriter.start();
        tReader.start();

        tReader.join();
        running[0] = false;
        tWriter.join();

        if (failed[0]) {
            fail(errorMsg[0]);
        }

        List<StockBook> finalStock = getStockFor(isbns);
        assertEquals(isbns.length, finalStock.size());

        int copies = -1;
        for (StockBook book : finalStock) {
            if (copies == -1) {
                copies = book.getNumCopies();
            } else {
                assertEquals("inconsistent snapshot", copies, book.getNumCopies());
            }
        }
    }
}
