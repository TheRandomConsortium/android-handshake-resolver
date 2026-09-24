cmake_minimum_required(VERSION 3.22.1)
project("hsk_jni" C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

add_definitions(
    -DHSK_BUILD
    -DHAVE_CONFIG_H=1
    -D_GNU_SOURCE
    -D_POSIX_C_SOURCE=200809L
)

include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}/hsk
    ${CMAKE_CURRENT_SOURCE_DIR}/hsk/secp256k1
    ${CMAKE_CURRENT_SOURCE_DIR}/hsk/chacha20
    ${CMAKE_CURRENT_SOURCE_DIR}/hsk/poly1305
    ${CMAKE_CURRENT_SOURCE_DIR}/uv/include
    ${CMAKE_CURRENT_SOURCE_DIR}/uv/src
)

set(UV_SOURCES
    uv/src/fs-poll.c
    uv/src/inet.c
    uv/src/threadpool.c
    uv/src/uv-data-getter-setters.c
    uv/src/uv-common.c
    uv/src/version.c
    uv/src/unix/async.c
    uv/src/unix/core.c
    uv/src/unix/fs.c
    uv/src/unix/loop.c
    uv/src/unix/loop-watcher.c
    uv/src/unix/pipe.c
    uv/src/unix/poll.c
    uv/src/unix/process.c
    uv/src/unix/signal.c
    uv/src/unix/stream.c
    uv/src/unix/tcp.c
    uv/src/unix/thread.c
    uv/src/unix/timer.c
    uv/src/unix/tty.c
    uv/src/unix/udp.c
    uv/src/unix/linux-core.c
    uv/src/unix/linux-inotify.c
    uv/src/unix/linux-syscalls.c
    uv/src/unix/proctitle.c
    uv/src/unix/android-ifaddrs.c
    uv/src/unix/pthread-fixes.c
)

set(HSK_SOURCES
    hsk/addr.c
    hsk/addrmgr.c
    hsk/aead.c
    hsk/base32.c
    hsk/blake2b.c
    hsk/bn.c
    hsk/brontide.c
    hsk/cache.c
    hsk/chacha20/chacha20.c
    hsk/chain.c
    hsk/dns.c
    hsk/dnssec.c
    hsk/ecc.c
    hsk/ec.c
    hsk/error.c
    hsk/hash.c
    hsk/header.c
    hsk/hesiod.c
    hsk/map.c
    hsk/msg.c
    hsk/ns.c
    hsk/poly1305/poly1305.c
    hsk/pool.c
    hsk/proof.c
    hsk/random.c
    hsk/req.c
    hsk/resource.c
    hsk/secp256k1/secp256k1.c
    hsk/sha256.c
    hsk/sha3.c
    hsk/sig0.c
    hsk/siphash.c
    hsk/store.c
    hsk/timedata.c
    hsk/utils.c
)

add_library(hsk_jni SHARED
    hnsd_jni.c
    ${HSK_SOURCES}
    ${UV_SOURCES}
)

find_library(log-lib log)

target_link_libraries(hsk_jni
    ${log-lib}
)
