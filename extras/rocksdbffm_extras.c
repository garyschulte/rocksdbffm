/*
 * rocksdbffm_extras.c — custom helpers compiled into librocksdb.
 *
 * These functions extend the RocksDB C API to reduce the number of
 * Panama downcalls required per get() operation in the Java binding.
 * The hot byte[] path currently makes 3 downcalls per read:
 *   rocksdb_get_pinned_cf → rocksdb_pinnableslice_value → rocksdb_pinnableslice_destroy
 * The helper below collapses those 3 into 1.
 */
#include "rocksdb/c.h"
#include <stddef.h>
#include <string.h>

/*
 * size_t rocksdbffm_get_cf_into(rocksdb_t* db,
 *     const rocksdb_readoptions_t* options,
 *     rocksdb_column_family_handle_t* cf,
 *     const char* key, size_t keylen,
 *     char* outbuf, size_t outbuf_len,
 *     char** errptr)
 *
 * Pins the value for `key` in `cf`, copies it into `outbuf` if it fits,
 * then destroys the pin — all in one C call.
 *
 * Return value:
 *   (size_t)-1     key not found; *errptr is unchanged (remains NULL)
 *   <= outbuf_len  actual value length; bytes copied to outbuf
 *   > outbuf_len   actual value length; outbuf NOT written; caller retries
 *                  with a freshly allocated buffer of at least that size
 *
 * On RocksDB error: *errptr is set to a malloc'd string (free with rocksdb_free);
 * return value is 0.
 */
size_t rocksdbffm_get_cf_into(
		rocksdb_t* db,
		const rocksdb_readoptions_t* options,
		rocksdb_column_family_handle_t* cf,
		const char* key, size_t keylen,
		char* outbuf, size_t outbuf_len,
		char** errptr)
{
	rocksdb_pinnableslice_t* pin = rocksdb_get_pinned_cf(db, options, cf, key, keylen, errptr);
	if (*errptr) return 0;
	if (!pin) return (size_t)-1;
	size_t vlen;
	const char* vdata = rocksdb_pinnableslice_value(pin, &vlen);
	if (vlen <= outbuf_len) {
		memcpy(outbuf, vdata, vlen);
	}
	rocksdb_pinnableslice_destroy(pin);
	return vlen;
}
