package com.acertainbookstore.client.workloads;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import com.acertainbookstore.business.ImmutableStockBook;
import com.acertainbookstore.business.StockBook;

/**
 * Helper class to generate stockbooks and isbns modelled similar to Random
 * class
 */
public class BookSetGenerator {

	private Random random;

	public BookSetGenerator() {
		random = new Random();
	}

	/**
	 * Returns num randomly selected isbns from the input set
	 *
	 * @param num
	 * @return
	 */
	public Set<Integer> sampleFromSetOfISBNs(Set<Integer> isbns, int num) {
		if (isbns == null || isbns.isEmpty()) {
			return new HashSet<>();
		}

		List<Integer> list = new ArrayList<>(isbns);
		// If the request num is larger than the set size, return the whole set
		if (num >= list.size()) {
			return new HashSet<>(list);
		}

		Collections.shuffle(list);
		return new HashSet<>(list.subList(0, num));
	}

	/**
	 * Return num stock books. For now return an ImmutableStockBook
	 *
	 * @param num
	 * @return
	 */
	public Set<StockBook> nextSetOfStockBooks(int num) {
		Set<StockBook> stockBooks = new HashSet<>();
		for (int i = 0; i < num; i++) {
			// Generate a random ISBN (using a large range to minimize collisions)
			int isbn = random.nextInt(Integer.MAX_VALUE - 1) + 1;
			String title = "BookTitle-" + System.nanoTime();
			String author = "Author-" + System.nanoTime();
			float price = 10 + random.nextFloat() * 100; // Price between 10 and 110
			int copies = 50 + random.nextInt(100); // Copies between 50 and 149
			long numSaleMisses = 0;
			long numTimesRated = 0;
			long totalRating = 0;
			boolean editorPick = false; // Default false

			stockBooks.add(new ImmutableStockBook(isbn, title, author, price, copies, numSaleMisses, numTimesRated,
					totalRating, editorPick));
		}
		return stockBooks;
	}

}