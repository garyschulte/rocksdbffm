package io.github.dfa1.rocksdbffm;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/// FFM wrapper for `rocksdb_writeoptions_t`.
public final class WriteOptions extends NativeObject {

	/// `rocksdb_writeoptions_t* rocksdb_writeoptions_create(void);`
	private static final MethodHandle MH_CREATE;
	/// `void rocksdb_writeoptions_destroy(rocksdb_writeoptions_t*);`
	private static final MethodHandle MH_DESTROY;
	/// `void rocksdb_writeoptions_set_ignore_missing_column_families(rocksdb_writeoptions_t*, unsigned char);`
	private static final MethodHandle MH_SET_IGNORE_MISSING_COLUMN_FAMILIES;
	/// `void rocksdb_writeoptions_set_no_slowdown(rocksdb_writeoptions_t*, unsigned char);`
	private static final MethodHandle MH_SET_NO_SLOWDOWN;
	/// `void rocksdb_writeoptions_set_low_pri(rocksdb_writeoptions_t*, unsigned char);`
	private static final MethodHandle MH_SET_LOW_PRI;

	static {
		MH_CREATE = NativeLibrary.lookup("rocksdb_writeoptions_create",
				FunctionDescriptor.of(ValueLayout.ADDRESS));

		MH_DESTROY = NativeLibrary.lookup("rocksdb_writeoptions_destroy",
				FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

		MH_SET_IGNORE_MISSING_COLUMN_FAMILIES = NativeLibrary.lookup(
				"rocksdb_writeoptions_set_ignore_missing_column_families",
				FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_BYTE));

		MH_SET_NO_SLOWDOWN = NativeLibrary.lookup("rocksdb_writeoptions_set_no_slowdown",
				FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_BYTE));

		MH_SET_LOW_PRI = NativeLibrary.lookup("rocksdb_writeoptions_set_low_pri",
				FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_BYTE));
	}

	private WriteOptions(MemorySegment ptr) {
		super(ptr);
	}

	/// Creates a new [WriteOptions] with default settings.
	///
	/// @return a new [WriteOptions]; caller must close it
	public static WriteOptions newWriteOptions() {
		try {
			return new WriteOptions((MemorySegment) MH_CREATE.invokeExact());
		} catch (Throwable t) {
			throw new RocksDBException("writeoptions create failed", t);
		}
	}

	/// When `true`, writes to a column family that no longer exists are silently ignored
	/// rather than returning an error. Useful when column families may be created or dropped
	/// concurrently with ongoing transactions.
	///
	/// @param ignore `true` to ignore writes to missing column families
	/// @return `this` for chaining
	public WriteOptions setIgnoreMissingColumnFamilies(boolean ignore) {
		try {
			MH_SET_IGNORE_MISSING_COLUMN_FAMILIES.invokeExact(ptr(), ignore ? (byte) 1 : (byte) 0);
			return this;
		} catch (Throwable t) {
			throw new RocksDBException("writeoptions setIgnoreMissingColumnFamilies failed", t);
		}
	}

	/// When `true`, the write returns immediately with an error rather than waiting if
	/// RocksDB is in a write-stall condition. Useful for best-effort deletes where missing
	/// the delete is preferable to blocking the caller.
	///
	/// @param noSlowdown `true` to fail fast instead of blocking on write stalls
	/// @return `this` for chaining
	public WriteOptions setNoSlowdown(boolean noSlowdown) {
		try {
			MH_SET_NO_SLOWDOWN.invokeExact(ptr(), noSlowdown ? (byte) 1 : (byte) 0);
			return this;
		} catch (Throwable t) {
			throw new RocksDBException("writeoptions setNoSlowdown failed", t);
		}
	}

	/// Marks this write as low priority. Low-priority writes yield to high-priority writes
	/// under write pressure and are throttled by the rate limiter. Use for background or
	/// bulk-import writes that should not compete with foreground block processing.
	///
	/// @param lowPri `true` to mark this write as low priority
	/// @return `this` for chaining
	public WriteOptions setLowPri(boolean lowPri) {
		try {
			MH_SET_LOW_PRI.invokeExact(ptr(), lowPri ? (byte) 1 : (byte) 0);
			return this;
		} catch (Throwable t) {
			throw new RocksDBException("writeoptions setLowPri failed", t);
		}
	}

	@Override
	protected void tryClose(MemorySegment ptr) throws Throwable {
		MH_DESTROY.invokeExact(ptr);
	}
}
