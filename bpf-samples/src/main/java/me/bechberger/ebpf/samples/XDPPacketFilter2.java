package me.bechberger.ebpf.samples;

import me.bechberger.ebpf.annotations.bpf.BPF;
import me.bechberger.ebpf.annotations.bpf.BPFMapDefinition;
import me.bechberger.ebpf.bpf.BPFProgram;
import me.bechberger.ebpf.bpf.NetworkUtil;
import me.bechberger.ebpf.bpf.map.BPFHashMap;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Use XDP to block incoming packages from specific URLs
 * <p>
 * Based on the code from <a href="https://sematext.com/blog/ebpf-and-xdp-for-processing-packets-at-bare-metal-speed/">sematext.com</a>.
 * Albeit this code can be found in many other places, as
 * it is the most straightforward example of using XDP to block incoming packages.
 * <p>
 * This is the old version of {@link XDPPacketFilter2} which is implemented without the Java compiler plugin
 * and C code in a string.
 */
@BPF(license = "GPL")
@Command(name = "XDPPacketFilter", mixinStandardHelpOptions = true,
        description = "Use XDP to block incoming IPv4 packages from a URLs")
public abstract class XDPPacketFilter2 extends BPFProgram implements Runnable {

    @BPFMapDefinition(maxEntries = 256 * 4096)
    BPFHashMap<Integer, Boolean> blockedIPs;

    @BPFMapDefinition(maxEntries = 256 * 4096)
    BPFHashMap<Integer, Integer> blockingStats;

    private static final String EBPF_PROGRAM = """
            #include <vmlinux.h>
            #include <linux/types.h>
            #include <bpf/bpf_helpers.h>
            #include <bpf/bpf_endian.h>
            
            // copied from the linux kernel
            #define ETH_P_8021Q 0x8100
            #define ETH_P_8021AD 0x88A8
            #define ETH_P_IP 0x0800
            #define ETH_P_IPV6 0x86DD
            #define ETH_P_ARP 0x0806
            
            SEC("xdp")
            int xdp_pass(struct xdp_md *ctx) {
                void *end = (void *)(long)ctx->data_end;
                void *data = (void *)(long)ctx->data;
                u32 ip_src;
                u64 offset;
                u16 eth_type;
            
                struct ethhdr *eth = data;
                offset = sizeof(*eth);
            
                if (data + offset > end) {
                    // ethernet package header is incomplete
                    return XDP_ABORTED;
                }
                eth_type = eth->h_proto;
            
                // handle VLAN tagged packet
                if (eth_type == bpf_htons(ETH_P_8021Q) || eth_type == bpf_htons(ETH_P_8021AD)) {
                    struct vlan_hdr *vlan_hdr;
            
                    vlan_hdr = (void *)eth + offset;
                    offset += sizeof(*vlan_hdr);
                    if ((void *)eth + offset > end) {
                        // ethernet package header is incomplete
                        return false;
                    }
                    eth_type = vlan_hdr->h_vlan_encapsulated_proto;
                }
            
                // let's only handle IPv4 addresses
                if (eth_type != bpf_htons(ETH_P_IP)) {
                    return XDP_PASS;
                }
            
                struct iphdr *iph = data + offset;
                offset += sizeof(struct iphdr);
                // make sure the bytes you want to read are within the packet's range before reading them
                if (iph + 1 > end) {
                    return XDP_ABORTED;
                }
                ip_src = iph->saddr;
            
                // find entry in block list
                void* ret = (void*)bpf_map_lookup_elem(&blockedIPs, &ip_src);
                if (!ret) {
                    return XDP_PASS;
                }
                
                // count the number of blocked packages
                s32* counter = bpf_map_lookup_elem(&blockingStats, &ip_src);
                if (counter) {
                    // use atomics to prevent a race condition when a packet
                    // from the same IP address is received on two
                    // different cores at the same time
                    // (thanks Dylan Reimerink for catching this bug)
                    __sync_fetch_and_add(counter, 1);
                } else {
                    u64 value = 1;
                    bpf_map_update_elem(&blockingStats, &ip_src, &value, BPF_ANY);
                }
            
                return XDP_DROP;
            }
            """;

    @Parameters(arity = "1..*", description = "URLs to block")
    private String[] blockedUrls;

    @Option(names = "--run-url-retrieve-loop", description = "Try to retrieve the content of the first URL in a loop")
    private boolean runURLRetrieveLoop;

    private Map<Integer, String> ipToUrlMap;

    void setupBlockedIPMap() {
        ipToUrlMap = Arrays.stream(blockedUrls).flatMap(url -> {
            try {
                return Arrays.stream(InetAddress.getAllByName(url)).map(addr -> Map.entry(NetworkUtil.ipAddressToInt(addr), url));
            } catch (UnknownHostException e) {
                throw new RuntimeException(e);
            }
        }).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        ipToUrlMap.keySet().forEach(ip -> {
            blockedIPs.put(ip, true);
        });
    }

    void printBlockedLog() {
        System.out.println("Blocked packages:");
        blockingStats.forEach((ip, count) -> {
            System.out.println("  Blocked " + count + " packages from " +
                    NetworkUtil.intToIpAddress(ip) +
                    " (" + ipToUrlMap.get(ip) + ")");
        });
    }

    @Override
    public void run() {
        setupBlockedIPMap();
        if (runURLRetrieveLoop) {
            NetworkUtil.openURLInLoop(blockedUrls[0]);
        }
        xdpAttach(getProgramByName("xdp_pass"), NetworkUtil.getNetworkInterfaceIndex());
        while (true) {
            printBlockedLog();
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static void main(String[] args) {
        try (XDPPacketFilter2 program = BPFProgram.load(XDPPacketFilter2.class)) {
            var cmd = new CommandLine(program);
            cmd.parseArgs(args);
            if (cmd.isUsageHelpRequested()) {
                cmd.usage(System.out);
                return;
            }
            program.run();
        }
    }
}
