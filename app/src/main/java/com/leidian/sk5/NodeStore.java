package com.leidian.sk5;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 节点与设置的本地存储，只有一个文件，方便导出与排查。 */
public final class NodeStore {

    private static final String PREFS = "sk5_store";
    private static final String KEY_NODES = "nodes";
    private static final String KEY_CURRENT = "current";
    private static final String KEY_GLOBAL = "global_mode";
    private static final String KEY_AUTO = "auto_connect";
    private static final String KEY_STACK = "tun_stack";
    private static final String KEY_FOLLOW = "follow_interface";
    private static final String KEY_DNS = "dns_mode";

    private static NodeStore instance;

    private final SharedPreferences prefs;
    private final List<SocksNode> nodes = new ArrayList<>();
    private int currentIndex = -1;
    private boolean globalMode;
    private boolean autoConnect;
    private String stack = CoreOptions.DEFAULT_STACK;
    private boolean followInterface = true;
    private String dnsMode = CoreOptions.DEFAULT_DNS;

    private NodeStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    public static synchronized NodeStore get(Context context) {
        if (instance == null) {
            instance = new NodeStore(context);
        }
        return instance;
    }

    private void load() {
        nodes.clear();
        try {
            JSONArray array = new JSONArray(prefs.getString(KEY_NODES, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                SocksNode node = new SocksNode();
                node.name = o.optString("name", "");
                node.host = o.optString("host", "");
                node.port = o.optInt("port", 1080);
                node.username = o.optString("username", "");
                node.password = o.optString("password", "");
                if (node.isValid()) {
                    nodes.add(node);
                }
            }
        } catch (Exception e) {
            LogRecorder.error("存储", "读取节点失败", e);
        }
        currentIndex = prefs.getInt(KEY_CURRENT, nodes.isEmpty() ? -1 : 0);
        if (currentIndex >= nodes.size()) {
            currentIndex = nodes.isEmpty() ? -1 : 0;
        }
        globalMode = prefs.getBoolean(KEY_GLOBAL, false);
        autoConnect = prefs.getBoolean(KEY_AUTO, false);
        stack = prefs.getString(KEY_STACK, CoreOptions.DEFAULT_STACK);
        followInterface = prefs.getBoolean(KEY_FOLLOW, true);
        dnsMode = prefs.getString(KEY_DNS, CoreOptions.DEFAULT_DNS);
    }

    public List<SocksNode> nodes() {
        return new ArrayList<>(nodes);
    }

    public int size() {
        return nodes.size();
    }

    public int currentIndex() {
        return currentIndex;
    }

    public SocksNode current() {
        if (currentIndex < 0 || currentIndex >= nodes.size()) {
            return null;
        }
        return nodes.get(currentIndex);
    }

    public void select(int index) {
        if (index < 0 || index >= nodes.size()) {
            return;
        }
        currentIndex = index;
        prefs.edit().putInt(KEY_CURRENT, index).apply();
    }

    /** @param replaceIndex 传 -1 表示新增，否则覆盖该位置 */
    public void saveNode(SocksNode node, int replaceIndex) {
        if (replaceIndex >= 0 && replaceIndex < nodes.size()) {
            nodes.set(replaceIndex, node);
        } else {
            nodes.add(node);
            currentIndex = nodes.size() - 1;
        }
        persistNodes();
    }

    public void delete(int index) {
        if (index < 0 || index >= nodes.size()) {
            return;
        }
        nodes.remove(index);
        if (nodes.isEmpty()) {
            currentIndex = -1;
        } else if (currentIndex >= nodes.size()) {
            currentIndex = nodes.size() - 1;
        }
        persistNodes();
    }

    private void persistNodes() {
        JSONArray array = new JSONArray();
        for (SocksNode node : nodes) {
            try {
                JSONObject o = new JSONObject();
                o.put("name", node.name);
                o.put("host", node.host);
                o.put("port", node.port);
                o.put("username", node.username);
                o.put("password", node.password);
                array.put(o);
            } catch (Exception e) {
                LogRecorder.error("存储", "写入节点失败", e);
            }
        }
        prefs.edit()
                .putString(KEY_NODES, array.toString())
                .putInt(KEY_CURRENT, currentIndex)
                .apply();
    }

    public boolean globalMode() {
        return globalMode;
    }

    public void setGlobalMode(boolean value) {
        globalMode = value;
        prefs.edit().putBoolean(KEY_GLOBAL, value).apply();
    }

    public boolean autoConnect() {
        return autoConnect;
    }

    public void setAutoConnect(boolean value) {
        autoConnect = value;
        prefs.edit().putBoolean(KEY_AUTO, value).apply();
    }

    public String stack() {
        return stack;
    }

    public void setStack(String value) {
        stack = CoreOptions.STACK_SYSTEM.equals(value)
                ? CoreOptions.STACK_SYSTEM : CoreOptions.STACK_GVISOR;
        prefs.edit().putString(KEY_STACK, stack).apply();
    }

    public boolean followInterface() {
        return followInterface;
    }

    public void setFollowInterface(boolean value) {
        followInterface = value;
        prefs.edit().putBoolean(KEY_FOLLOW, value).apply();
    }

    public String dnsMode() {
        return dnsMode;
    }

    public void setDnsMode(String value) {
        dnsMode = CoreOptions.DNS_DOMESTIC.equals(value)
                ? CoreOptions.DNS_DOMESTIC : CoreOptions.DNS_REMOTE;
        prefs.edit().putString(KEY_DNS, dnsMode).apply();
    }
}
