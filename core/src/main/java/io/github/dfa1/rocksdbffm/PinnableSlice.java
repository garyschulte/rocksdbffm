package io.github.dfa1.rocksdbffm;

import java.lang.foreign.MemorySegment;

/// A pinned value obtained from [ReadWriteDB#getPinned].
///
/// Holds a reference to a RocksDB block-cache page by incrementing its reference count. The
/// [#data()] segment is valid until [#close()] is called; accessing it afterward is undefined
/// behaviour. Callers must close this object promptly — holding it open prevents block-cache
/// eviction of the pinned page.
///
/// ```
/// try (PinnableSlice slice = db.getPinned(cf, readOpts, key).orElseThrow()) {
///     byte[] value = slice.data().toArray(ValueLayout.JAVA_BYTE);
/// }
/// ```
public final class PinnableSlice extends NativeObject {

	private final MemorySegment data;

	/// Package-private constructor — obtained via [RocksDB#openPinnedCf].
	///
	/// @param pin  the `rocksdb_pinnableslice_t*` pointer that owns the block-cache pin
	/// @param data native segment pointing into the pinned block-cache page
	PinnableSlice(MemorySegment pin, MemorySegment data) {
		super(pin);
		this.data = data;
	}

	/// Returns the native memory segment containing the value bytes.
	///
	/// The segment is valid only while this slice is open. Do not retain references to it beyond
	/// the lifetime of this object.
	///
	/// @return the native value segment
	public MemorySegment data() {
		return data;
	}

	/// Returns the size of the value in bytes.
	///
	/// @return value byte count
	public long size() {
		return data.byteSize();
	}

	@Override
	protected void tryClose(final MemorySegment ptr) {
		RocksDB.destroyPin(ptr);
	}
}
