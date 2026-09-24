#ifndef _HSK_CONFIG_H
#define _HSK_CONFIG_H

#define PACKAGE_NAME "hnsd"
#define PACKAGE_VERSION "2.99.0"

#define HAVE_ARPA_INET_H 1
#define HAVE_BUILTIN_EXPECT 1
#define HAVE_FCNTL_H 1
#define HAVE_INTTYPES_H 1
#define HAVE_LIMITS_H 1
#define HAVE_MALLOC 1
#define HAVE_MEMSET 1
#define HAVE_NETINET_IN_H 1
#define HAVE_RANDOM 1
#define HAVE_REALLOC 1
#define HAVE_STDDEF_H 1
#define HAVE_STDINT_H 1
#define HAVE_STDIO_H 1
#define HAVE_STDLIB_H 1
#define HAVE_STRCASECMP 1
#define HAVE_STRCHR 1
#define HAVE_STRDUP 1
#define HAVE_STRINGS_H 1
#define HAVE_STRING_H 1
#define HAVE_SYS_STAT_H 1
#define HAVE_SYS_TYPES_H 1
#define HAVE_UNISTD_H 1
#define HAVE__BOOL 1

#define HSK_NETWORK 0 /* HSK_MAIN */
#define HSK_USE_ECMULT_STATIC_PRECOMPUTATION 1

#if defined(__x86_64__) || defined(__aarch64__)
#define HAVE___INT128 1
#define HSK_USE_FIELD_5X52 1
#define HSK_USE_SCALAR_4X64 1
#else
#define HSK_USE_FIELD_10X26 1
#define HSK_USE_SCALAR_8X32 1
#endif

#endif /* _HSK_CONFIG_H */
