#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <android/log.h>

#include "hsk/config.h"
#include "hsk/constants.h"
#include "hsk/error.h"
#include "hsk/pool.h"
#include "hsk/ns.h"
#include "hsk/store.h"
#include "hsk/tld.h"
#include "uv/include/uv.h"

#define TAG "HnsdNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

typedef struct {
  uv_loop_t *loop;
  hsk_pool_t *pool;
  hsk_ns_t *ns;
  uv_async_t stop_async;
  char *prefix;
  int ns_port;
  bool running;
  pthread_t thread;
  pthread_mutex_t lock;
} hnsd_ctx_t;

static hnsd_ctx_t g_ctx = {
  .loop = NULL,
  .pool = NULL,
  .ns = NULL,
  .prefix = NULL,
  .ns_port = 5349,
  .running = false,
  .lock = PTHREAD_MUTEX_INITIALIZER
};

static void on_stop_async(uv_async_t *handle) {
  LOGI("on_stop_async received, shutting down loop...");
  hnsd_ctx_t *ctx = (hnsd_ctx_t *)handle->data;

  if (ctx->ns) {
    hsk_ns_close(ctx->ns);
  }

  if (ctx->pool) {
    hsk_pool_close(ctx->pool);
  }

  uv_close((uv_handle_t *)&ctx->stop_async, NULL);
  uv_stop(ctx->loop);
}

static void *hnsd_worker_thread(void *arg) {
  hnsd_ctx_t *ctx = (hnsd_ctx_t *)arg;
  LOGI("hnsd worker thread started on port %d, prefix: %s", ctx->ns_port, ctx->prefix);

  ctx->loop = uv_loop_new();
  if (!ctx->loop) {
    LOGE("Failed to create uv loop");
    ctx->running = false;
    return NULL;
  }

  ctx->pool = hsk_pool_alloc(ctx->loop);
  if (!ctx->pool) {
    LOGE("Failed to allocate pool");
    uv_loop_delete(ctx->loop);
    ctx->loop = NULL;
    ctx->running = false;
    return NULL;
  }

  hsk_pool_set_size(ctx->pool, 8);

  ctx->ns = hsk_ns_alloc(ctx->loop, ctx->pool);
  if (!ctx->ns) {
    LOGE("Failed to allocate ns");
    hsk_pool_free(ctx->pool);
    ctx->pool = NULL;
    uv_loop_delete(ctx->loop);
    ctx->loop = NULL;
    ctx->running = false;
    return NULL;
  }

  // Inject hardcoded checkpoint if available
  if (HSK_CHECKPOINT != NULL) {
    uint8_t *data = (uint8_t *)HSK_CHECKPOINT;
    size_t data_len = HSK_STORE_CHECKPOINT_SIZE;
    if (hsk_store_inject_checkpoint(&data, &data_len, &ctx->pool->chain)) {
      LOGI("Injected hardcoded checkpoint into chain");
    } else {
      LOGE("Failed to inject hardcoded checkpoint");
    }
  }

  // Checkpoint & chain storage directory
  if (ctx->prefix) {
    if (hsk_store_exists(ctx->prefix)) {
      ctx->pool->chain.prefix = ctx->prefix;
      uint8_t data[HSK_STORE_CHECKPOINT_SIZE];
      uint8_t *data_ptr = (uint8_t *)&data;
      size_t data_len = HSK_STORE_CHECKPOINT_SIZE;
      if (hsk_store_read(&data_ptr, &data_len, &ctx->pool->chain)) {
        if (hsk_store_inject_checkpoint(&data_ptr, &data_len, &ctx->pool->chain)) {
          LOGI("Injected checkpoint from file storage");
        }
      }
    }
  }

  // Open pool and root nameserver
  int rc = hsk_pool_open(ctx->pool);
  if (rc != HSK_SUCCESS) {
    LOGE("Failed to open pool: %s", hsk_strerror(rc));
    hsk_ns_free(ctx->ns);
    ctx->ns = NULL;
    hsk_pool_free(ctx->pool);
    ctx->pool = NULL;
    uv_loop_delete(ctx->loop);
    ctx->loop = NULL;
    ctx->running = false;
    return NULL;
  }

  struct sockaddr_in addr;
  memset(&addr, 0, sizeof(addr));
  addr.sin_family = AF_INET;
  addr.sin_port = htons(ctx->ns_port);
  addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);

  rc = hsk_ns_open(ctx->ns, (const struct sockaddr *)&addr);
  if (rc != HSK_SUCCESS) {
    LOGE("Failed to open ns on port %d: %s", ctx->ns_port, hsk_strerror(rc));
    hsk_pool_close(ctx->pool);
    hsk_ns_free(ctx->ns);
    ctx->ns = NULL;
    hsk_pool_free(ctx->pool);
    ctx->pool = NULL;
    uv_loop_delete(ctx->loop);
    ctx->loop = NULL;
    ctx->running = false;
    return NULL;
  }

  // Async handle for stopping the loop safely from another thread
  uv_async_init(ctx->loop, &ctx->stop_async, on_stop_async);
  ctx->stop_async.data = (void *)ctx;

  LOGI("hnsd event loop running...");
  uv_run(ctx->loop, UV_RUN_DEFAULT);
  LOGI("hnsd event loop finished.");

  // Cleanup
  if (ctx->ns) {
    hsk_ns_free(ctx->ns);
    ctx->ns = NULL;
  }
  if (ctx->pool) {
    hsk_pool_free(ctx->pool);
    ctx->pool = NULL;
  }
  if (ctx->loop) {
    uv_loop_close(ctx->loop);
    uv_loop_delete(ctx->loop);
    ctx->loop = NULL;
  }

  pthread_mutex_lock(&ctx->lock);
  ctx->running = false;
  pthread_mutex_unlock(&ctx->lock);

  return NULL;
}

JNIEXPORT jboolean JNICALL
Java_org_handshake_resolver_engine_HnsdNative_nativeStart(
    JNIEnv *env, jobject thiz, jstring jDataDir, jint jNsPort) {
  pthread_mutex_lock(&g_ctx.lock);

  if (g_ctx.running) {
    pthread_mutex_unlock(&g_ctx.lock);
    LOGI("hnsd already running");
    return JNI_TRUE;
  }

  const char *data_dir = (*env)->GetStringUTFChars(env, jDataDir, NULL);
  if (g_ctx.prefix) {
    free(g_ctx.prefix);
  }
  g_ctx.prefix = strdup(data_dir);
  (*env)->ReleaseStringUTFChars(env, jDataDir, data_dir);

  g_ctx.ns_port = (int)jNsPort;
  g_ctx.running = true;

  if (pthread_create(&g_ctx.thread, NULL, hnsd_worker_thread, &g_ctx) != 0) {
    LOGE("Failed to spawn hnsd thread");
    g_ctx.running = false;
    pthread_mutex_unlock(&g_ctx.lock);
    return JNI_FALSE;
  }

  pthread_mutex_unlock(&g_ctx.lock);
  LOGI("hnsd nativeStart initiated successfully");
  return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_org_handshake_resolver_engine_HnsdNative_nativeStop(
    JNIEnv *env, jobject thiz) {
  pthread_mutex_lock(&g_ctx.lock);

  if (!g_ctx.running || !g_ctx.loop) {
    pthread_mutex_unlock(&g_ctx.lock);
    return;
  }

  LOGI("Stopping hnsd native...");
  uv_async_send(&g_ctx.stop_async);
  pthread_mutex_unlock(&g_ctx.lock);

  pthread_join(g_ctx.thread, NULL);
  LOGI("hnsd thread stopped and joined.");
}

JNIEXPORT jfloat JNICALL
Java_org_handshake_resolver_engine_HnsdNative_nativeGetProgress(
    JNIEnv *env, jobject thiz) {
  pthread_mutex_lock(&g_ctx.lock);
  if (!g_ctx.running || !g_ctx.pool) {
    pthread_mutex_unlock(&g_ctx.lock);
    return 0.0f;
  }

  float progress = hsk_chain_progress(&g_ctx.pool->chain);
  pthread_mutex_unlock(&g_ctx.lock);
  return (jfloat)progress;
}

JNIEXPORT jint JNICALL
Java_org_handshake_resolver_engine_HnsdNative_nativeGetHeight(
    JNIEnv *env, jobject thiz) {
  pthread_mutex_lock(&g_ctx.lock);
  if (!g_ctx.running || !g_ctx.pool || !g_ctx.pool->chain.tip) {
    pthread_mutex_unlock(&g_ctx.lock);
    return 0;
  }

  int height = (int)g_ctx.pool->chain.tip->height;
  pthread_mutex_unlock(&g_ctx.lock);
  return (jint)height;
}

JNIEXPORT jboolean JNICALL
Java_org_handshake_resolver_engine_HnsdNative_nativeIsSynced(
    JNIEnv *env, jobject thiz) {
  pthread_mutex_lock(&g_ctx.lock);
  if (!g_ctx.running || !g_ctx.pool) {
    pthread_mutex_unlock(&g_ctx.lock);
    return JNI_FALSE;
  }

  bool synced = hsk_chain_synced(&g_ctx.pool->chain);
  pthread_mutex_unlock(&g_ctx.lock);
  return synced ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_handshake_resolver_engine_HnsdNative_nativeIsRunning(
    JNIEnv *env, jobject thiz) {
  pthread_mutex_lock(&g_ctx.lock);
  jboolean running = g_ctx.running ? JNI_TRUE : JNI_FALSE;
  pthread_mutex_unlock(&g_ctx.lock);
  return running;
}

JNIEXPORT jboolean JNICALL
Java_org_handshake_resolver_engine_HnsdNative_nativeIsIcannTld(
    JNIEnv *env, jobject thiz, jstring jTld) {
  if (!jTld) return JNI_FALSE;
  const char *tld = (*env)->GetStringUTFChars(env, jTld, NULL);
  if (!tld) return JNI_FALSE;

  int start = 0;
  int end = HSK_TLD_SIZE - 1;
  int found = 0;

  while (start <= end) {
    int pos = (start + end) >> 1;
    int cmp = strcasecmp(HSK_TLD_NAMES[pos], tld);
    if (cmp == 0) {
      found = 1;
      break;
    }
    if (cmp < 0) {
      start = pos + 1;
    } else {
      end = pos - 1;
    }
  }

  (*env)->ReleaseStringUTFChars(env, jTld, tld);
  return found ? JNI_TRUE : JNI_FALSE;
}

