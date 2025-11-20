package com.acertainbookstore.utils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;

import com.acertainbookstore.business.ImmutableStockBook;
import com.acertainbookstore.interfaces.BookStoreSerializer;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.util.DefaultInstantiatorStrategy;
import org.objenesis.strategy.StdInstantiatorStrategy;

/**
 * {@link BookStoreKryoSerializer} serializes objects to arrays of bytes
 * representing strings using the Kryo library.
 * 
 * @see BookStoreSerializer
 */
public final class BookStoreKryoSerializer implements BookStoreSerializer {

	/** The binary stream. */
	private final Kryo binaryStream;

	/**
	 * Instantiates a new {@link BookStoreKryoSerializer}.
	 */
	public BookStoreKryoSerializer() {
		binaryStream = new Kryo();
		binaryStream.setInstantiatorStrategy(new DefaultInstantiatorStrategy(new StdInstantiatorStrategy()));

		// register all classes
		binaryStream.register(ImmutableStockBook.class);
		binaryStream.register(HashSet.class);
		binaryStream.register(BookStoreResponse.class);
		binaryStream.register(com.acertainbookstore.business.BookCopy.class);
		binaryStream.register(java.util.ArrayList.class);
		binaryStream.register(com.acertainbookstore.business.BookEditorPick.class);
		binaryStream.register(com.acertainbookstore.business.ImmutableBook.class);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * com.acertainbookstore.interfaces.BookStoreSerializer#serialize(java.lang.
	 * Object)
	 */
	@Override
	public byte[] serialize(Object object) throws IOException {
		try (ByteArrayOutputStream outStream = new ByteArrayOutputStream(); Output out = new Output(outStream)) {
			binaryStream.writeClassAndObject(out, object);
			out.flush();
			return outStream.toByteArray();
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.acertainbookstore.interfaces.BookStoreSerializer#deserialize(byte[])
	 */
	@Override
	public Object deserialize(byte[] bytes) throws IOException {
		try (InputStream inStream = new ByteArrayInputStream(bytes); Input in = new Input(inStream)) {
			return binaryStream.readClassAndObject(in);
		}
	}
}
