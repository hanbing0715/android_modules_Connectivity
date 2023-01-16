/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.connectivity.mdns;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TargetApi;
import android.net.LinkAddress;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Looper;
import android.util.ArraySet;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.net.module.util.CollectionUtils;
import com.android.net.module.util.HexDump;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * A repository of records advertised through {@link MdnsInterfaceAdvertiser}.
 *
 * Must be used on a consistent looper thread.
 */
@TargetApi(Build.VERSION_CODES.TIRAMISU) // Allow calling T+ APIs; this is only loaded on T+
public class MdnsRecordRepository {
    // TTLs as per RFC6762 10.
    // TTL for records with a host name as the resource record's name (e.g., A, AAAA, HINFO) or a
    // host name contained within the resource record's rdata (e.g., SRV, reverse mapping PTR
    // record)
    private static final long NAME_RECORDS_TTL_MILLIS = TimeUnit.SECONDS.toMillis(120);
    // TTL for other records
    private static final long NON_NAME_RECORDS_TTL_MILLIS = TimeUnit.MINUTES.toMillis(75);

    // Top-level domain for link-local queries, as per RFC6762 3.
    private static final String LOCAL_TLD = "local";

    // Service type for service enumeration (RFC6763 9.)
    private static final String[] DNS_SD_SERVICE_TYPE =
            new String[] { "_services", "_dns-sd", "_udp", LOCAL_TLD };

    // Map of service unique ID -> records for service
    @NonNull
    private final SparseArray<ServiceRegistration> mServices = new SparseArray<>();
    @NonNull
    private final List<RecordInfo<?>> mGeneralRecords = new ArrayList<>();
    @NonNull
    private final Looper mLooper;
    @NonNull
    private String[] mDeviceHostname;

    public MdnsRecordRepository(@NonNull Looper looper) {
        this(looper, new Dependencies());
    }

    @VisibleForTesting
    public MdnsRecordRepository(@NonNull Looper looper, @NonNull Dependencies deps) {
        mDeviceHostname = deps.getHostname();
        mLooper = looper;
    }

    /**
     * Dependencies to use with {@link MdnsRecordRepository}, useful for testing.
     */
    @VisibleForTesting
    public static class Dependencies {
        /**
         * Get a unique hostname to be used by the device.
         */
        @NonNull
        public String[] getHostname() {
            // Generate a very-probably-unique hostname. This allows minimizing possible conflicts
            // to the point that probing for it is no longer necessary (as per RFC6762 8.1 last
            // paragraph), and does not leak more information than what could already be obtained by
            // looking at the mDNS packets source address.
            // This differs from historical behavior that just used "Android.local" for many
            // devices, creating a lot of conflicts.
            // Having a different hostname per interface is an acceptable option as per RFC6762 14.
            // This hostname will change every time the interface is reconnected, so this does not
            // allow tracking the device.
            // TODO: consider deriving a hostname from other sources, such as the IPv6 addresses
            // (reusing the same privacy-protecting mechanics).
            return new String[] {
                    "Android_" + UUID.randomUUID().toString().replace("-", ""), LOCAL_TLD };
        }

        /**
         * @see NetworkInterface#getInetAddresses().
         */
        @NonNull
        public Enumeration<InetAddress> getInterfaceInetAddresses(@NonNull NetworkInterface iface) {
            return iface.getInetAddresses();
        }
    }

    private static class RecordInfo<T extends MdnsRecord> {
        public final T record;
        public final NsdServiceInfo serviceInfo;

        /**
         * Whether the name of this record is expected to be fully owned by the service or may be
         * advertised by other hosts as well (shared).
         */
        public final boolean isSharedName;

        /**
         * Whether probing is still in progress for the record.
         */
        public boolean isProbing;

        RecordInfo(NsdServiceInfo serviceInfo, T record, boolean sharedName,
                 boolean probing) {
            this.serviceInfo = serviceInfo;
            this.record = record;
            this.isSharedName = sharedName;
            this.isProbing = probing;
        }
    }

    private static class ServiceRegistration {
        @NonNull
        public final List<RecordInfo<?>> allRecords;
        @NonNull
        public final RecordInfo<MdnsPointerRecord> ptrRecord;
        @NonNull
        public final RecordInfo<MdnsServiceRecord> srvRecord;
        @NonNull
        public final RecordInfo<MdnsTextRecord> txtRecord;
        @NonNull
        public final NsdServiceInfo serviceInfo;

        /**
         * Whether the service is sending exit announcements and will be destroyed soon.
         */
        public boolean exiting = false;

        /**
         * Create a ServiceRegistration for dns-sd service registration (RFC6763).
         *
         * @param deviceHostname Hostname of the device (for the interface used)
         * @param serviceInfo Service to advertise
         */
        ServiceRegistration(@NonNull String[] deviceHostname, @NonNull NsdServiceInfo serviceInfo) {
            this.serviceInfo = serviceInfo;

            final String[] serviceType = splitServiceType(serviceInfo);
            final String[] serviceName = splitFullyQualifiedName(serviceInfo, serviceType);

            // Service PTR record
            ptrRecord = new RecordInfo<>(
                    serviceInfo,
                    new MdnsPointerRecord(
                            serviceType,
                            0L /* receiptTimeMillis */,
                            false /* cacheFlush */,
                            NON_NAME_RECORDS_TTL_MILLIS,
                            serviceName),
                    true /* sharedName */, true /* probing */);

            srvRecord = new RecordInfo<>(
                    serviceInfo,
                    new MdnsServiceRecord(serviceName,
                            0L /* receiptTimeMillis */,
                            true /* cacheFlush */,
                            NAME_RECORDS_TTL_MILLIS, 0 /* servicePriority */, 0 /* serviceWeight */,
                            serviceInfo.getPort(),
                            deviceHostname),
                    false /* sharedName */, true /* probing */);

            txtRecord = new RecordInfo<>(
                    serviceInfo,
                    new MdnsTextRecord(serviceName,
                            0L /* receiptTimeMillis */,
                            true /* cacheFlush */, // Service name is verified unique after probing
                            NON_NAME_RECORDS_TTL_MILLIS,
                            attrsToTextEntries(serviceInfo.getAttributes())),
                    false /* sharedName */, true /* probing */);

            final ArrayList<RecordInfo<?>> allRecords = new ArrayList<>(4);
            allRecords.add(ptrRecord);
            allRecords.add(srvRecord);
            allRecords.add(txtRecord);
            // Service type enumeration record (RFC6763 9.)
            allRecords.add(new RecordInfo<>(
                    serviceInfo,
                    new MdnsPointerRecord(
                            DNS_SD_SERVICE_TYPE,
                            0L /* receiptTimeMillis */,
                            false /* cacheFlush */,
                            NON_NAME_RECORDS_TTL_MILLIS,
                            serviceType),
                    true /* sharedName */, true /* probing */));

            this.allRecords = Collections.unmodifiableList(allRecords);
        }

        void setProbing(boolean probing) {
            for (RecordInfo<?> info : allRecords) {
                info.isProbing = probing;
            }
        }
    }

    /**
     * Inform the repository of the latest interface addresses.
     */
    public void updateAddresses(@NonNull List<LinkAddress> newAddresses) {
        mGeneralRecords.clear();
        for (LinkAddress addr : newAddresses) {
            final String[] revDnsAddr = getReverseDnsAddress(addr.getAddress());
            mGeneralRecords.add(new RecordInfo<>(
                    null /* serviceInfo */,
                    new MdnsPointerRecord(
                            revDnsAddr,
                            0L /* receiptTimeMillis */,
                            true /* cacheFlush */,
                            NAME_RECORDS_TTL_MILLIS,
                            mDeviceHostname),
                    false /* sharedName */, false /* probing */));

            mGeneralRecords.add(new RecordInfo<>(
                    null /* serviceInfo */,
                    new MdnsInetAddressRecord(
                            mDeviceHostname,
                            0L /* receiptTimeMillis */,
                            true /* cacheFlush */,
                            NAME_RECORDS_TTL_MILLIS,
                            addr.getAddress()),
                    false /* sharedName */, false /* probing */));
        }
    }

    /**
     * Add a service to the repository.
     *
     * This may remove/replace any existing service that used the name added but is exiting.
     * @param serviceId A unique service ID.
     * @param serviceInfo Service info to add.
     * @return If the added service replaced another with a matching name (which was exiting), the
     *         ID of the replaced service.
     * @throws NameConflictException There is already a (non-exiting) service using the name.
     */
    public int addService(int serviceId, NsdServiceInfo serviceInfo) throws NameConflictException {
        if (mServices.contains(serviceId)) {
            throw new IllegalArgumentException(
                    "Service ID must not be reused across registrations: " + serviceId);
        }

        final int existing = getServiceByName(serviceInfo.getServiceName());
        // It's OK to re-add a service that is exiting
        if (existing >= 0 && !mServices.get(existing).exiting) {
            throw new NameConflictException(existing);
        }

        final ServiceRegistration registration = new ServiceRegistration(
                mDeviceHostname, serviceInfo);
        mServices.put(serviceId, registration);

        // Remove existing exiting service
        mServices.remove(existing);
        return existing;
    }

    /**
     * @return The ID of the service identified by its name, or -1 if none.
     */
    private int getServiceByName(@NonNull String serviceName) {
        for (int i = 0; i < mServices.size(); i++) {
            final ServiceRegistration registration = mServices.valueAt(i);
            if (serviceName.equals(registration.serviceInfo.getServiceName())) {
                return mServices.keyAt(i);
            }
        }
        return -1;
    }

    private MdnsProber.ProbingInfo makeProbingInfo(int serviceId,
            @NonNull MdnsServiceRecord srvRecord) {
        final List<MdnsRecord> probingRecords = new ArrayList<>();
        // Probe with cacheFlush cleared; it is set when announcing, as it was verified unique:
        // RFC6762 10.2
        probingRecords.add(new MdnsServiceRecord(srvRecord.getName(),
                0L /* receiptTimeMillis */,
                false /* cacheFlush */,
                srvRecord.getTtl(),
                srvRecord.getServicePriority(), srvRecord.getServiceWeight(),
                srvRecord.getServicePort(),
                srvRecord.getServiceHost()));

        return new MdnsProber.ProbingInfo(serviceId, probingRecords);
    }

    private static List<MdnsServiceInfo.TextEntry> attrsToTextEntries(Map<String, byte[]> attrs) {
        final List<MdnsServiceInfo.TextEntry> out = new ArrayList<>(attrs.size());
        for (Map.Entry<String, byte[]> attr : attrs.entrySet()) {
            out.add(new MdnsServiceInfo.TextEntry(attr.getKey(), attr.getValue()));
        }
        return out;
    }

    /**
     * Mark a service in the repository as exiting.
     * @param id ID of the service, used at registration time.
     * @return The exit announcement to indicate the service was removed, or null if not necessary.
     */
    @Nullable
    public MdnsAnnouncer.AnnouncementInfo exitService(int id) {
        final ServiceRegistration registration = mServices.get(id);
        if (registration == null) return null;
        if (registration.exiting) return null;

        registration.exiting = true;

        // TODO: implement
        return null;
    }

    /**
     * Remove a service from the repository
     */
    public void removeService(int id) {
        mServices.remove(id);
    }

    /**
     * @return The number of services currently held in the repository, including exiting services.
     */
    public int getServicesCount() {
        return mServices.size();
    }

    /**
     * Remove all services from the repository
     * @return IDs of the removed services
     */
    @NonNull
    public int[] clearServices() {
        final int[] ret = new int[mServices.size()];
        for (int i = 0; i < mServices.size(); i++) {
            ret[i] = mServices.keyAt(i);
        }
        mServices.clear();
        return ret;
    }

    /**
     * Add NSEC records indicating that the response records are unique.
     *
     * Following RFC6762 6.1:
     * "On receipt of a question for a particular name, rrtype, and rrclass, for which a responder
     * does have one or more unique answers, the responder MAY also include an NSEC record in the
     * Additional Record Section indicating the nonexistence of other rrtypes for that name and
     * rrclass."
     * @param destinationList List to add the NSEC records to.
     * @param answerRecords Lists of answered records based on which to add NSEC records (typically
     *                      answer and additionalAnswer sections)
     */
    @SafeVarargs
    private static void addNsecRecordsForUniqueNames(
            List<MdnsRecord> destinationList,
            Iterator<RecordInfo<?>>... answerRecords) {
        // Group unique records by name. Use a TreeMap with comparator as arrays don't implement
        // equals / hashCode.
        final Map<String[], List<MdnsRecord>> nsecByName = new TreeMap<>(Arrays::compare);
        // But keep the list of names in added order, otherwise records would be sorted in
        // alphabetical order instead of the order of the original records, which would look like
        // inconsistent behavior depending on service name.
        final List<String[]> namesInAddedOrder = new ArrayList<>();
        for (Iterator<RecordInfo<?>> answers : answerRecords) {
            addNonSharedRecordsToMap(answers, nsecByName, namesInAddedOrder);
        }

        for (String[] nsecName : namesInAddedOrder) {
            final List<MdnsRecord> entryRecords = nsecByName.get(nsecName);
            long minTtl = Long.MAX_VALUE;
            final Set<Integer> types = new ArraySet<>(entryRecords.size());
            for (MdnsRecord record : entryRecords) {
                if (minTtl > record.getTtl()) minTtl = record.getTtl();
                types.add(record.getType());
            }

            destinationList.add(new MdnsNsecRecord(
                    nsecName,
                    0L /* receiptTimeMillis */,
                    true /* cacheFlush */,
                    minTtl,
                    nsecName,
                    CollectionUtils.toIntArray(types)));
        }
    }

    /**
     * Add non-shared records to a map listing them by record name, and to a list of names that
     * remembers the adding order.
     *
     * In the destination map records are grouped by name; so the map has one key per record name,
     * and the values are the lists of different records that share the same name.
     * @param records Records to scan.
     * @param dest Map to add the records to.
     * @param namesInAddedOrder List of names to add the names in order, keeping the first
     *                          occurrence of each name.
     */
    private static void addNonSharedRecordsToMap(
            Iterator<RecordInfo<?>> records,
            Map<String[], List<MdnsRecord>> dest,
            List<String[]> namesInAddedOrder) {
        while (records.hasNext()) {
            final RecordInfo<?> record = records.next();
            if (record.isSharedName) continue;
            final List<MdnsRecord> recordsForName = dest.computeIfAbsent(record.record.name,
                    key -> {
                        namesInAddedOrder.add(key);
                        return new ArrayList<>();
                    });
            recordsForName.add(record.record);
        }
    }

    /**
     * Called to indicate that probing succeeded for a service.
     * @param probeSuccessInfo The successful probing info.
     * @return The {@link MdnsAnnouncer.AnnouncementInfo} to send, now that probing has succeeded.
     */
    public MdnsAnnouncer.AnnouncementInfo onProbingSucceeded(
            MdnsProber.ProbingInfo probeSuccessInfo)
            throws IOException {

        final ServiceRegistration registration = mServices.get(probeSuccessInfo.getServiceId());
        if (registration == null) throw new IOException(
                "Service is not registered: " + probeSuccessInfo.getServiceId());
        registration.setProbing(false);

        final ArrayList<MdnsRecord> answers = new ArrayList<>();
        final ArrayList<MdnsRecord> additionalAnswers = new ArrayList<>();

        // Interface address records in general records
        for (RecordInfo<?> record : mGeneralRecords) {
            answers.add(record.record);
        }

        // All service records
        for (RecordInfo<?> info : registration.allRecords) {
            answers.add(info.record);
        }

        addNsecRecordsForUniqueNames(additionalAnswers,
                mGeneralRecords.iterator(), registration.allRecords.iterator());

        return new MdnsAnnouncer.AnnouncementInfo(answers, additionalAnswers);
    }

    /**
     * (Re)set a service to the probing state.
     * @return The {@link MdnsProber.ProbingInfo} to send for probing.
     */
    @Nullable
    public MdnsProber.ProbingInfo setServiceProbing(int serviceId) {
        final ServiceRegistration registration = mServices.get(serviceId);
        if (registration == null) return null;

        registration.setProbing(true);
        return makeProbingInfo(serviceId, registration.srvRecord.record);
    }

    /**
     * Indicates whether a given service is in probing state.
     */
    public boolean isProbing(int serviceId) {
        final ServiceRegistration registration = mServices.get(serviceId);
        if (registration == null) return false;

        return registration.srvRecord.isProbing;
    }

    /**
     * Compute:
     * 2001:db8::1 --> 1.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.8.B.D.1.0.0.2.ip6.arpa
     *
     * Or:
     * 192.0.2.123 --> 123.2.0.192.in-addr.arpa
     */
    @VisibleForTesting
    public static String[] getReverseDnsAddress(@NonNull InetAddress addr) {
        // xxx.xxx.xxx.xxx.in-addr.arpa (up to 28 characters)
        // or 32 hex characters separated by dots + .ip6.arpa
        final byte[] addrBytes = addr.getAddress();
        final List<String> out = new ArrayList<>();
        if (addr instanceof Inet4Address) {
            for (int i = addrBytes.length - 1; i >= 0; i--) {
                out.add(String.valueOf(Byte.toUnsignedInt(addrBytes[i])));
            }
            out.add("in-addr");
        } else {
            final String hexAddr = HexDump.toHexString(addrBytes);

            for (int i = hexAddr.length() - 1; i >= 0; i--) {
                out.add(String.valueOf(hexAddr.charAt(i)));
            }
            out.add("ip6");
        }
        out.add("arpa");

        return out.toArray(new String[0]);
    }

    private static String[] splitFullyQualifiedName(
            @NonNull NsdServiceInfo info, @NonNull String[] serviceType) {
        final String[] split = new String[serviceType.length + 1];
        split[0] = info.getServiceName();
        System.arraycopy(serviceType, 0, split, 1, serviceType.length);

        return split;
    }

    private static String[] splitServiceType(@NonNull NsdServiceInfo info) {
        // String.split(pattern, 0) removes trailing empty strings, which would appear when
        // splitting "domain.name." (with a dot a the end), so this is what is needed here.
        final String[] split = info.getServiceType().split("\\.", 0);
        final String[] type = new String[split.length + 1];
        System.arraycopy(split, 0, type, 0, split.length);
        type[split.length] = LOCAL_TLD;

        return type;
    }
}
