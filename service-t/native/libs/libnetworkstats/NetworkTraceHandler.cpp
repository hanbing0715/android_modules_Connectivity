/*
 * Copyright (C) 2023 The Android Open Source Project
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

#define LOG_TAG "NetworkTrace"

#include "netdbpf/NetworkTraceHandler.h"

#include <arpa/inet.h>
#include <bpf/BpfUtils.h>
#include <log/log.h>
#include <perfetto/config/android/network_trace_config.pbzero.h>
#include <perfetto/trace/android/network_trace.pbzero.h>
#include <perfetto/trace/profiling/profile_packet.pbzero.h>
#include <perfetto/tracing/platform.h>
#include <perfetto/tracing/tracing.h>

// Note: this is initializing state for a templated Perfetto type that resides
// in the `perfetto` namespace. This must be defined in the global scope.
PERFETTO_DEFINE_DATA_SOURCE_STATIC_MEMBERS(android::bpf::NetworkTraceHandler);

namespace android {
namespace bpf {
using ::android::bpf::internal::NetworkTracePoller;
using ::perfetto::protos::pbzero::NetworkPacketEvent;
using ::perfetto::protos::pbzero::NetworkPacketTraceConfig;
using ::perfetto::protos::pbzero::TracePacket;
using ::perfetto::protos::pbzero::TrafficDirection;

// Bundling takes groups of packets with similar contextual fields (generally,
// all fields except timestamp and length) and summarises them in a single trace
// packet. For example, rather than
//
//   {.timestampNs = 1, .uid = 1000, .tag = 123, .len = 72}
//   {.timestampNs = 2, .uid = 1000, .tag = 123, .len = 100}
//   {.timestampNs = 5, .uid = 1000, .tag = 123, .len = 456}
//
// The output will be something like
//   {
//     .timestamp = 1
//     .ctx = {.uid = 1000, .tag = 123}
//     .timestamp = [0, 1, 4], // delta encoded
//     .length = [72, 100, 456], // should be zipped with timestamps
//   }
//
// Most workloads have many packets from few contexts. Bundling greatly reduces
// the amount of redundant information written, thus reducing the overall trace
// size. Interning ids are similarly based on unique bundle contexts.

// Keys are PacketTraces where timestamp and length are ignored.
using BundleKey = PacketTrace;

// Based on boost::hash_combine
template <typename T, typename... Rest>
void HashCombine(std::size_t& seed, const T& val, const Rest&... rest) {
  seed ^= std::hash<T>()(val) + 0x9e3779b9 + (seed << 6) + (seed >> 2);
  (HashCombine(seed, rest), ...);
}

// Details summarises the timestamp and lengths of packets in a bundle.
struct BundleDetails {
  std::vector<std::pair<uint64_t, uint32_t>> time_and_len;
  uint64_t minTs = std::numeric_limits<uint64_t>::max();
  uint64_t maxTs = std::numeric_limits<uint64_t>::min();
  uint32_t bytes = 0;
};

#define AGG_FIELDS(x)                                              \
  (x).ifindex, (x).uid, (x).tag, (x).sport, (x).dport, (x).egress, \
      (x).ipProto, (x).tcpFlags

struct BundleHash {
  std::size_t operator()(const BundleKey& a) const {
    std::size_t seed = 0;
    HashCombine(seed, AGG_FIELDS(a));
    return seed;
  }
};

struct BundleEq {
  bool operator()(const BundleKey& a, const BundleKey& b) const {
    return std::tie(AGG_FIELDS(a)) == std::tie(AGG_FIELDS(b));
  }
};

// static
void NetworkTraceHandler::RegisterDataSource() {
  ALOGD("Registering Perfetto data source");
  perfetto::DataSourceDescriptor dsd;
  dsd.set_name("android.network_packets");
  NetworkTraceHandler::Register(dsd);
}

// static
void NetworkTraceHandler::InitPerfettoTracing() {
  perfetto::TracingInitArgs args = {};
  args.backends |= perfetto::kSystemBackend;
  args.enable_system_consumer = false;
  perfetto::Tracing::Initialize(args);
  NetworkTraceHandler::RegisterDataSource();
}

// static
NetworkTracePoller NetworkTraceHandler::sPoller(
    [](const std::vector<PacketTrace>& packets) {
      // Trace calls the provided callback for each active session. The context
      // gets a reference to the NetworkTraceHandler instance associated with
      // the session and delegates writing. The corresponding handler will write
      // with the setting specified in the trace config.
      NetworkTraceHandler::Trace([&](NetworkTraceHandler::TraceContext ctx) {
        ctx.GetDataSourceLocked()->Write(packets, ctx);
      });
    });

void NetworkTraceHandler::OnSetup(const SetupArgs& args) {
  const std::string& raw = args.config->network_packet_trace_config_raw();
  NetworkPacketTraceConfig::Decoder config(raw);

  mPollMs = config.poll_ms();
  if (mPollMs < 100) {
    ALOGI("poll_ms is missing or below the 100ms minimum. Increasing to 100ms");
    mPollMs = 100;
  }

  mInternLimit = config.intern_limit();
  mAggregationThreshold = config.aggregation_threshold();
  mDropLocalPort = config.drop_local_port();
  mDropRemotePort = config.drop_remote_port();
  mDropTcpFlags = config.drop_tcp_flags();
}

void NetworkTraceHandler::OnStart(const StartArgs&) {
  mStarted = sPoller.Start(mPollMs);
}

void NetworkTraceHandler::OnStop(const StopArgs&) {
  if (mStarted) sPoller.Stop();
  mStarted = false;
}

void NetworkTraceHandler::Write(const std::vector<PacketTrace>& packets,
                                NetworkTraceHandler::TraceContext& ctx) {
  // TODO: remove this fallback once Perfetto stable has support for bundles.
  if (!mInternLimit && !mAggregationThreshold) {
    for (const PacketTrace& pkt : packets) {
      auto dst = ctx.NewTracePacket();
      dst->set_timestamp(pkt.timestampNs);
      auto* event = dst->set_network_packet();
      event->set_length(pkt.length);
      Fill(pkt, event);
    }
    return;
  }
  std::unordered_map<BundleKey, BundleDetails, BundleHash, BundleEq> bundles;
  for (const PacketTrace& pkt : packets) {
    BundleDetails& bundle = bundles[pkt];
    bundle.time_and_len.emplace_back(pkt.timestampNs, pkt.length);
    bundle.minTs = std::min(bundle.minTs, pkt.timestampNs);
    bundle.maxTs = std::max(bundle.maxTs, pkt.timestampNs);
    bundle.bytes += pkt.length;
  }

  for (const auto& kv : bundles) {
    const BundleKey& key = kv.first;
    const BundleDetails& details = kv.second;

    auto dst = ctx.NewTracePacket();
    dst->set_timestamp(details.minTs);

    auto* event = dst->set_network_packet_bundle();
    Fill(key, event->set_ctx());

    protozero::PackedVarInt offsets;
    protozero::PackedVarInt lengths;
    for (const auto& kv : details.time_and_len) {
      offsets.Append(kv.first - details.minTs);
      lengths.Append(kv.second);
    }

    event->set_packet_timestamps(offsets);
    event->set_packet_lengths(lengths);
  }
}

// static class method
void NetworkTraceHandler::Fill(const PacketTrace& src,
                               NetworkPacketEvent* event) {
  event->set_direction(src.egress ? TrafficDirection::DIR_EGRESS
                                  : TrafficDirection::DIR_INGRESS);
  event->set_uid(src.uid);
  event->set_tag(src.tag);

  event->set_local_port(src.egress ? ntohs(src.sport) : ntohs(src.dport));
  event->set_remote_port(src.egress ? ntohs(src.dport) : ntohs(src.sport));

  event->set_ip_proto(src.ipProto);
  event->set_tcp_flags(src.tcpFlags);

  char ifname[IF_NAMESIZE] = {};
  if (if_indextoname(src.ifindex, ifname) == ifname) {
    event->set_interface(std::string(ifname));
  } else {
    event->set_interface("error");
  }
}

}  // namespace bpf
}  // namespace android
