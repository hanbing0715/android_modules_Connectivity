/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#pragma once

#include <errno.h>
#include <linux/if_ether.h>
#include <linux/pfkeyv2.h>
#include <net/if.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/utsname.h>

#include <log/log.h>

#include "KernelVersion.h"

namespace android {
namespace bpf {

constexpr const uint64_t NONEXISTENT_COOKIE = 0;

static inline uint64_t getSocketCookie(int sockFd) {
    uint64_t sock_cookie;
    socklen_t cookie_len = sizeof(sock_cookie);
    int res = getsockopt(sockFd, SOL_SOCKET, SO_COOKIE, &sock_cookie, &cookie_len);
    if (res < 0) {
        res = -errno;
        ALOGE("Failed to get socket cookie: %s\n", strerror(errno));
        errno = -res;
        // 0 is an invalid cookie. See sock_gen_cookie.
        return NONEXISTENT_COOKIE;
    }
    return sock_cookie;
}

static inline int synchronizeKernelRCU() {
    // This is a temporary hack for network stats map swap on devices running
    // 4.9 kernels. The kernel code of socket release on pf_key socket will
    // explicitly call synchronize_rcu() which is exactly what we need.
    //
    // Linux 4.14/4.19/5.4/5.10/5.15 (and 5.18) still have this same behaviour.
    // see net/key/af_key.c: pfkey_release() -> synchronize_rcu()
    int pfSocket = socket(AF_KEY, SOCK_RAW | SOCK_CLOEXEC, PF_KEY_V2);

    if (pfSocket < 0) {
        int ret = -errno;
        ALOGE("create PF_KEY socket failed: %s", strerror(errno));
        return ret;
    }

    // When closing socket, synchronize_rcu() gets called in sock_release().
    if (close(pfSocket)) {
        int ret = -errno;
        ALOGE("failed to close the PF_KEY socket: %s", strerror(errno));
        return ret;
    }
    return 0;
}

static inline int setrlimitForTest() {
    // Set the memory rlimit for the test process if the default MEMLOCK rlimit is not enough.
    struct rlimit limit = {
            .rlim_cur = 1073741824,  // 1 GiB
            .rlim_max = 1073741824,  // 1 GiB
    };
    int res = setrlimit(RLIMIT_MEMLOCK, &limit);
    if (res) {
        ALOGE("Failed to set the default MEMLOCK rlimit: %s", strerror(errno));
    }
    return res;
}

}  // namespace bpf
}  // namespace android
