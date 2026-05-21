package io.github.dfa1.rocksdbffm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class WithPinnedValueTest {

	// -----------------------------------------------------------------------
	// ReadWriteDB — basic get and missing-key paths
	// -----------------------------------------------------------------------

	@Test
	void withPinnedValue_returnsReaderResult_whenKeyExists(@TempDir Path dir) {
		// Given
		try (var db = RocksDB.open(dir);
		     var cf = db.createColumnFamily(ColumnFamilyDescriptor.of("cf1"));
		     var readOpts = ReadOptions.newReadOptions()) {
			db.put(cf, "hello".getBytes(), "world".getBytes());

			// When
			Optional<byte[]> result = db.withPinnedValue(cf, readOpts, "hello".getBytes(),
					seg -> seg.toArray(ValueLayout.JAVA_BYTE));

			// Then
			assertThat(result).isPresent().contains("world".getBytes());
		}
	}

	@Test
	void withPinnedValue_returnsEmpty_whenKeyMissing(@TempDir Path dir) {
		// Given
		try (var db = RocksDB.open(dir);
		     var cf = db.createColumnFamily(ColumnFamilyDescriptor.of("cf1"));
		     var readOpts = ReadOptions.newReadOptions()) {

			// When
			Optional<byte[]> result = db.withPinnedValue(cf, readOpts, "missing".getBytes(),
					seg -> seg.toArray(ValueLayout.JAVA_BYTE));

			// Then
			assertThat(result).isEmpty();
		}
	}

	@Test
	void withPinnedValue_readerReceivesCorrectSegmentLength(@TempDir Path dir) {
		// Given
		byte[] value = "rocksdb-ffm".getBytes();
		try (var db = RocksDB.open(dir);
		     var cf = db.createColumnFamily(ColumnFamilyDescriptor.of("cf1"));
		     var readOpts = ReadOptions.newReadOptions()) {
			db.put(cf, "k".getBytes(), value);

			// When
			Optional<Long> segLen = db.withPinnedValue(cf, readOpts, "k".getBytes(),
					seg -> seg.byteSize());

			// Then
			assertThat(segLen).isPresent().contains((long) value.length);
		}
	}

	// -----------------------------------------------------------------------
	// ReadWriteDB — snapshot isolation
	// -----------------------------------------------------------------------

	@Test
	void withPinnedValue_readsOldValue_throughSnapshot(@TempDir Path dir) {
		// Given
		try (var db = RocksDB.open(dir);
		     var cf = db.createColumnFamily(ColumnFamilyDescriptor.of("cf1"))) {
			db.put(cf, "k".getBytes(), "v1".getBytes());

			try (var snapshot = db.getSnapshot();
			     var readOpts = ReadOptions.newReadOptions()) {
				readOpts.setSnapshot(snapshot);

				// Overwrite after snapshot
				db.put(cf, "k".getBytes(), "v2".getBytes());

				// When
				Optional<byte[]> result = db.withPinnedValue(cf, readOpts, "k".getBytes(),
						seg -> seg.toArray(ValueLayout.JAVA_BYTE));

				// Then
				assertThat(result).isPresent().contains("v1".getBytes());
			}
		}
	}

	// -----------------------------------------------------------------------
	// ReadWriteDB — multiple values to verify no cross-contamination
	// -----------------------------------------------------------------------

	@Test
	void withPinnedValue_canBeCalledRepeatedly_onSameCf(@TempDir Path dir) {
		// Given
		try (var db = RocksDB.open(dir);
		     var cf = db.createColumnFamily(ColumnFamilyDescriptor.of("cf1"));
		     var readOpts = ReadOptions.newReadOptions()) {
			db.put(cf, "a".getBytes(), "alpha".getBytes());
			db.put(cf, "b".getBytes(), "beta".getBytes());
			db.put(cf, "c".getBytes(), "gamma".getBytes());

			// When
			List<String> results = new ArrayList<>();
			for (String key : List.of("a", "b", "c")) {
				db.withPinnedValue(cf, readOpts, key.getBytes(),
						seg -> new String(seg.toArray(ValueLayout.JAVA_BYTE)))
						.ifPresent(results::add);
			}

			// Then
			assertThat(results).containsExactly("alpha", "beta", "gamma");
		}
	}

	// -----------------------------------------------------------------------
	// OptimisticTransactionDB — delegates to shared helper correctly
	// -----------------------------------------------------------------------

	@Test
	void withPinnedValue_worksOnOptimisticTransactionDB(@TempDir Path dir) {
		// Given
		try (var opts = Options.newOptions().setCreateIfMissing(true);
		     var db = RocksDB.openOptimistic(opts, dir);
		     var cf = db.createColumnFamily(ColumnFamilyDescriptor.of("cf1"));
		     var readOpts = ReadOptions.newReadOptions()) {
			db.put(cf, "key".getBytes(), "val".getBytes());

			// When
			Optional<byte[]> result = db.withPinnedValue(cf, readOpts, "key".getBytes(),
					seg -> seg.toArray(ValueLayout.JAVA_BYTE));

			// Then
			assertThat(result).isPresent().contains("val".getBytes());
		}
	}

	@Test
	void withPinnedValue_returnsEmpty_onOptimisticTransactionDB_whenKeyMissing(@TempDir Path dir) {
		// Given
		try (var opts = Options.newOptions().setCreateIfMissing(true);
		     var db = RocksDB.openOptimistic(opts, dir);
		     var cf = db.createColumnFamily(ColumnFamilyDescriptor.of("cf1"));
		     var readOpts = ReadOptions.newReadOptions()) {

			// When
			Optional<byte[]> result = db.withPinnedValue(cf, readOpts, "missing".getBytes(),
					seg -> seg.toArray(ValueLayout.JAVA_BYTE));

			// Then
			assertThat(result).isEmpty();
		}
	}

}
