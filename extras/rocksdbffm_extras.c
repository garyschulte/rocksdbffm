/*
 * rocksdbffm_extras.c — custom helpers compiled into librocksdb.
 *
 * Goal: reduce the 3-downcall byte[] get path to 2 downcalls without
 * introducing an extra data copy.
 *
 * Original 3-call path:
 *   1. rocksdb_get_pinned_cf   — expensive (DB lookup)
 *   2. rocksdb_pinnableslice_value — cheap (reads a pointer from a struct)
 *   3. rocksdb_pinnableslice_destroy — cheap (frees thin wrapper)
 *   Java does one toArray() copy between calls 2 and 3.
 *
 * New 2-call path:
 *   1. rocksdbffm_get_cf_open_pin  — combines calls 1+2 above (expensive)
 *   Java does the same single toArray() copy.
 *   2. rocksdb_pinnableslice_destroy — unchanged cheap call.
 *
 * This eliminates one Panama downcall with zero extra data copies.
 */
#include "rocksdb/c.h"
#include <stddef.h>

/*
 * size_t rocksdbffm_get_cf_open_pin(rocksdb_t* db,
 *     const rocksdb_readoptions_t* options,
 *     rocksdb_column_family_handle_t* cf,
 *     const char* key, size_t keylen,
 *     rocksdb_pinnableslice_t** pin_out,
 *     const char** data_out,
 *     char** errptr)
 *
 * Pins the value for `key` in `cf` and returns the raw data pointer and
 * value length in one call. Combines rocksdb_get_pinned_cf and
 * rocksdb_pinnableslice_value to eliminate one Panama downcall.
 *
 * On success:   *pin_out = live pin, *data_out = pointer to value bytes.
 *               Caller MUST call rocksdb_pinnableslice_destroy(*pin_out).
 *               Returns actual value length.
 * Not found:    *pin_out = NULL; returns (size_t)-1; *errptr unchanged.
 * On error:     *errptr set (free with rocksdb_free); returns 0.
 */
size_t rocksdbffm_get_cf_open_pin(
		rocksdb_t* db,
		const rocksdb_readoptions_t* options,
		rocksdb_column_family_handle_t* cf,
		const char* key, size_t keylen,
		rocksdb_pinnableslice_t** pin_out,
		const char** data_out,
		char** errptr)
{
	*pin_out = rocksdb_get_pinned_cf(db, options, cf, key, keylen, errptr);
	if (*errptr) return 0;
	if (!*pin_out) return (size_t)-1;
	size_t vlen;
	*data_out = rocksdb_pinnableslice_value(*pin_out, &vlen);
	return vlen;
}
