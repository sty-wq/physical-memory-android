// Process-local workaround for AEMU 37.1.11's missing property listener cleanup.
// Associates the listener registered immediately before CreateIOProcID with that
// IOProc and removes it before destruction. No microphone permissions are changed.
#include <CoreAudio/CoreAudio.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef struct Entry {
    AudioObjectID device;
    AudioObjectPropertyAddress property;
    AudioObjectPropertyListenerProc callback;
    void *context;
    AudioDeviceIOProcID io;
    struct Entry *next;
} Entry;
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static Entry *entries;
static _Thread_local Entry *pending;
__attribute__((constructor)) static void loaded(void) {
    fprintf(stderr, "aemu-listener-cleanup: loaded\n");
}

static int is_emulator_callback(AudioObjectPropertyListenerProc callback) {
    Dl_info info;
    if (!dladdr((const void *)callback, &info) || !info.dli_fname) return 0;
    const char *name = strrchr(info.dli_fname, '/');
    return strcmp(name ? name + 1 : info.dli_fname, "qemu-system-aarch64") == 0;
}

static OSStatus fixed_add(AudioObjectID device, const AudioObjectPropertyAddress *property,
                          AudioObjectPropertyListenerProc callback, void *context) {
    OSStatus result = AudioObjectAddPropertyListener(device, property, callback, context);
    // This is the only listener added by AEMU's coreaudio_init_base.
    if (result == noErr && property->mSelector == kAudioDevicePropertyNominalSampleRate &&
        property->mScope == kAudioObjectPropertyScopeGlobal && is_emulator_callback(callback)) {
        Entry *entry = calloc(1, sizeof(*entry));
        if (entry) {
            entry->device = device; entry->property = *property;
            entry->callback = callback; entry->context = context;
            pthread_mutex_lock(&lock);
            entry->next = entries; entries = entry;
            pthread_mutex_unlock(&lock);
            pending = entry;
        }
    }
    return result;
}

static OSStatus fixed_create(AudioDeviceID device, AudioDeviceIOProc callback,
                             void *context, AudioDeviceIOProcID *io) {
    OSStatus result = AudioDeviceCreateIOProcID(device, callback, context, io);
    pthread_mutex_lock(&lock);
    if (pending && pending->device == device) {
        if (result == noErr) {
            pending->io = *io;
        } else {
            AudioObjectRemovePropertyListener(device, &pending->property, pending->callback, pending->context);
            Entry **cursor = &entries;
            while (*cursor && *cursor != pending) cursor = &(*cursor)->next;
            if (*cursor) *cursor = pending->next;
            free(pending);
        }
    }
    pending = NULL;
    pthread_mutex_unlock(&lock);
    return result;
}

static OSStatus fixed_destroy(AudioDeviceID device, AudioDeviceIOProcID io) {
    // AEMU stops the device before calling DestroyIOProcID.
    pthread_mutex_lock(&lock);
    Entry **cursor = &entries;
    while (*cursor) {
        Entry *entry = *cursor;
        if (entry->device == device && entry->io == io) {
            OSStatus removed = AudioObjectRemovePropertyListener(device, &entry->property,
                                                                 entry->callback, entry->context);
            fprintf(stderr, "aemu-listener-cleanup: device=%u remove=%d\n", device, (int)removed);
            *cursor = entry->next;
            free(entry);
            break;
        }
        cursor = &entry->next;
    }
    pthread_mutex_unlock(&lock);
    return AudioDeviceDestroyIOProcID(device, io);
}

#define INTERPOSE(replacement, original) \
    __attribute__((used)) static struct { const void *replace; const void *original; } \
    pair_##original __attribute__((section("__DATA,__interpose"))) = { \
        (const void *)(replacement), (const void *)(original) };
INTERPOSE(fixed_add, AudioObjectAddPropertyListener)
INTERPOSE(fixed_create, AudioDeviceCreateIOProcID)
INTERPOSE(fixed_destroy, AudioDeviceDestroyIOProcID)
