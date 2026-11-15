package com.leidian.sk5;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity implements ProxyManager.Listener {

    private static final int REQ_VPN = 100;
    private static final int REQ_NOTIFICATION = 101;

    private NodeStore store;
    private ProxyManager proxy;

    private FrameLayout content;
    private LinearLayout navBar;
    private final LinearLayout[] pages = new LinearLayout[3];
    private final ImageView[] navIcons = new ImageView[3];
    private final TextView[] navLabels = new TextView[3];
    private boolean suppressAutoSave;

    private CircleButton circle;
    private TextView statusChip;
    private TextView nodeName;
    private TextView nodeEndpoint;
    private TextView nodeLatency;
    private LinearLayout nodeCard;

    private LinearLayout nodeList;
    private TextView nodeEmpty;

    private View modeSmart;
    private View modeGlobal;
    private TextView smartDesc;
    private TextView globalDesc;
    private Switch autoSwitch;
    private TextView stackGvisor;
    private TextView stackSystem;
    private TextView stackDesc;
    private Switch followSwitch;
    private TextView dnsRemote;
    private TextView dnsDomestic;
    private TextView dnsDesc;
    private TextView probeText;
    /** 手动自检进行中时置位，避免被自动自检的结果覆盖 */
    private Boolean probeRunning;

    private final Map<String, Integer> latencyByEndpoint = new HashMap<>();
    private final Handler ticker = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateDuration();
            ticker.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = NodeStore.get(this);
        proxy = ProxyManager.get();
        setContentView(buildRoot());
        proxy.setListener(this);
        applyInsets();
        switchTab(0);
        refreshAll();
        showCrashReportIfAny();
    }

    @Override
    protected void onResume() {
        super.onResume();
        proxy.setListener(this);
        refreshAll();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ticker.removeCallbacks(tick);
    }

    @Override
    protected void onDestroy() {
        proxy.setListener(null);
        super.onDestroy();
    }

    // ───────────────────────── 骨架 ─────────────────────────

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiKit.color(this, R.color.bg));

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        navBar = buildNav();
        root.addView(navBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        pages[0] = buildConnectPage();
        pages[1] = buildNodesPage();
        pages[2] = buildSettingsPage();
        for (LinearLayout page : pages) {
            content.addView(page, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        return root;
    }

    private void applyInsets() {
        View root = findViewById(android.R.id.content);
        if (root == null) {
            return;
        }
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
            content.setPadding(0, top, 0, 0);
            navBar.setPadding(0, 0, 0, bottom);
            return insets;
        });
    }

    private LinearLayout buildNav() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(UiKit.color(this, R.color.nav));
        bar.setElevation(UiKit.dp(this, 8));

        bar.addView(navItem(R.drawable.ic_nav_connect, R.string.tab_connect, 0));
        bar.addView(navItem(R.drawable.ic_nav_nodes, R.string.tab_nodes, 1));
        bar.addView(navItem(R.drawable.ic_nav_settings, R.string.tab_settings, 2));

        View topLine = UiKit.divider(this);
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.addView(topLine, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 1)));
        wrapper.addView(bar);
        wrapper.setBackgroundColor(UiKit.color(this, R.color.nav));
        navBar = wrapper;
        return wrapper;
    }

    private LinearLayout navItem(int iconRes, int labelRes, int index) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(0, UiKit.dp(this, 9), 0, UiKit.dp(this, 9));
        item.setClickable(true);

        ImageView icon = UiKit.icon(this, iconRes, 23);
        ((LinearLayout.LayoutParams) icon.getLayoutParams()).gravity = Gravity.CENTER;
        TextView label = UiKit.text(this, getString(labelRes), 11f,
                UiKit.color(this, R.color.text_secondary));
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, UiKit.dp(this, 3), 0, 0);

        item.addView(icon);
        item.addView(label);
        item.setOnClickListener(v -> switchTab(index));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        item.setLayoutParams(params);

        navIcons[index] = icon;
        navLabels[index] = label;
        return item;
    }

    private void switchTab(int index) {
        for (int i = 0; i < pages.length; i++) {
            if (pages[i] == null) {
                continue;
            }
            boolean active = i == index;
            pages[i].setVisibility(active ? View.VISIBLE : View.GONE);
            if (navLabels[i] != null) {
                navLabels[i].setTextColor(UiKit.color(this,
                        active ? R.color.brand : R.color.text_secondary));
                UiKit.tint(navIcons[i], UiKit.color(this,
                        active ? R.color.brand : R.color.text_secondary));
            }
        }
        if (index == 1) {
            refreshNodes();
        }
        if (index == 2) {
            refreshSettings();
        }
    }

    // ───────────────────────── 连接页 ─────────────────────────

    private LinearLayout buildConnectPage() {
        LinearLayout page = column();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        page.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout body = column();
        UiKit.setPadding(body, 20, 14, 20, 24);
        scroll.addView(body);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = UiKit.text(this, "SK5", 25f, UiKit.color(this, R.color.text_primary));
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        statusChip = UiKit.chip(this, getString(R.string.state_disconnected));
        header.addView(statusChip);
        body.addView(header);

        TextView tagline = UiKit.text(this, "轻量 SOCKS5 转发", 12f,
                UiKit.color(this, R.color.text_secondary));
        tagline.setPadding(0, UiKit.dp(this, 3), 0, 0);
        body.addView(tagline);

        circle = new CircleButton(this, null);
        circle.setOnClickListener(v -> onCircleClicked());
        LinearLayout.LayoutParams circleParams = new LinearLayout.LayoutParams(
                UiKit.dp(this, 208), UiKit.dp(this, 208));
        circleParams.gravity = Gravity.CENTER_HORIZONTAL;
        circleParams.topMargin = UiKit.dp(this, 12);
        circleParams.bottomMargin = UiKit.dp(this, 12);
        body.addView(circle, circleParams);

        body.addView(buildNodeCard());

        TextView linksTitle = UiKit.text(this, getString(R.string.links_title), 13f,
                UiKit.color(this, R.color.text_secondary));
        linksTitle.setPadding(0, UiKit.dp(this, 22), 0, UiKit.dp(this, 8));
        body.addView(linksTitle);

        TextView linksHint = UiKit.text(this, getString(R.string.links_hint), 11f,
                UiKit.color(this, R.color.text_secondary));
        linksHint.setPadding(0, 0, 0, UiKit.dp(this, 10));
        body.addView(linksHint);

        body.addView(linkRow(
                new String[]{getString(R.string.link_github), "github.com"},
                new String[]{getString(R.string.link_gitlab), "gitlab.com"}));
        body.addView(linkRow(
                new String[]{getString(R.string.link_sourceforge), "sourceforge.net"},
                new String[]{getString(R.string.link_huggingface), "huggingface.co"}));

        return page;
    }

    private LinearLayout buildNodeCard() {
        nodeCard = column();
        nodeCard.setBackgroundResource(R.drawable.bg_card);
        UiKit.setPadding(nodeCard, 16, 14, 16, 14);
        nodeCard.setClickable(true);
        nodeCard.setOnClickListener(v -> switchTab(1));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        nodeName = UiKit.text(this, getString(R.string.nodes_empty), 15f,
                UiKit.color(this, R.color.text_primary));
        nodeName.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        top.addView(nodeName, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        nodeLatency = UiKit.chip(this, getString(R.string.latency_none));
        top.addView(nodeLatency);
        nodeCard.addView(top);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(0, UiKit.dp(this, 5), 0, 0);

        nodeEndpoint = UiKit.text(this, getString(R.string.hint_no_node), 12f,
                UiKit.color(this, R.color.text_secondary));
        bottom.addView(nodeEndpoint, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView arrow = UiKit.icon(this, R.drawable.ic_chevron, 16);
        UiKit.tint(arrow, UiKit.color(this, R.color.text_secondary));
        bottom.addView(arrow);
        nodeCard.addView(bottom);

        return nodeCard;
    }

    private LinearLayout linkRow(String[] left, String[] right) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 0, 0, UiKit.dp(this, 10));
        row.addView(linkCard(left[0], left[1]));
        View gap = new View(this);
        gap.setLayoutParams(new LinearLayout.LayoutParams(UiKit.dp(this, 10), 1));
        row.addView(gap);
        row.addView(linkCard(right[0], right[1]));
        return row;
    }

    private LinearLayout linkCard(String name, String domain) {
        LinearLayout card = column();
        card.setBackgroundResource(R.drawable.bg_link_card);
        UiKit.setPadding(card, 14, 12, 14, 12);
        card.setClickable(true);
        card.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = UiKit.text(this, name, 14f, UiKit.color(this, R.color.text_primary));
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        card.addView(title);

        TextView sub = UiKit.text(this, domain, 11f, UiKit.color(this, R.color.text_secondary));
        sub.setPadding(0, UiKit.dp(this, 3), 0, 0);
        card.addView(sub);

        card.setOnClickListener(v -> openUrl("https://" + domain));
        return card;
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            toast("没有可用的浏览器");
        }
    }

    // ───────────────────────── 节点页 ─────────────────────────

    private LinearLayout buildNodesPage() {
        LinearLayout page = column();
        ScrollView scroll = new ScrollView(this);
        page.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout body = column();
        UiKit.setPadding(body, 20, 14, 20, 24);
        scroll.addView(body);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = UiKit.text(this, getString(R.string.tab_nodes), 22f,
                UiKit.color(this, R.color.text_primary));
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView add = UiKit.button(this, getString(R.string.node_new), true);
        add.setOnClickListener(v -> showNodeDialog(-1, null));
        header.addView(add);
        body.addView(header);

        TextView importButton = UiKit.button(this, getString(R.string.node_import), false);
        LinearLayout.LayoutParams importParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        importParams.topMargin = UiKit.dp(this, 14);
        importButton.setLayoutParams(importParams);
        importButton.setOnClickListener(v -> showImportDialog());
        body.addView(importButton);

        nodeList = column();
        nodeList.setPadding(0, UiKit.dp(this, 14), 0, 0);
        body.addView(nodeList);

        nodeEmpty = UiKit.text(this, getString(R.string.nodes_empty), 13f,
                UiKit.color(this, R.color.text_secondary));
        nodeEmpty.setGravity(Gravity.CENTER);
        nodeEmpty.setPadding(0, UiKit.dp(this, 30), 0, 0);
        body.addView(nodeEmpty);

        return page;
    }

    private void refreshNodes() {
        if (nodeList == null) {
            return;
        }
        nodeList.removeAllViews();
        List<SocksNode> nodes = store.nodes();
        nodeEmpty.setVisibility(nodes.isEmpty() ? View.VISIBLE : View.GONE);
        for (int i = 0; i < nodes.size(); i++) {
            final int index = i;
            SocksNode node = nodes.get(i);
            nodeList.addView(nodeRow(node, index));
        }
    }

    private LinearLayout nodeRow(SocksNode node, int index) {
        LinearLayout row = column();
        row.setBackgroundResource(R.drawable.bg_card);
        UiKit.setPadding(row, 16, 13, 16, 13);
        row.setClickable(true);
        boolean active = index == store.currentIndex();
        if (active) {
            row.setBackgroundResource(R.drawable.bg_option);
            row.setSelected(true);
        }

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = UiKit.text(this, node.displayName(), 15f,
                UiKit.color(this, R.color.text_primary));
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        top.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (active) {
            TextView inUse = UiKit.chip(this, getString(R.string.node_in_use));
            inUse.setTextColor(UiKit.color(this, R.color.brand));
            top.addView(inUse);
        }
        row.addView(top);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(0, UiKit.dp(this, 4), 0, 0);

        TextView endpoint = UiKit.text(this, node.toLink(), 11f,
                UiKit.color(this, R.color.text_secondary));
        bottom.addView(endpoint, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Integer cached = latencyByEndpoint.get(node.endpoint());
        TextView latency = UiKit.text(this,
                cached == null ? getString(R.string.latency_none)
                        : getString(R.string.latency_ms, cached),
                11f, UiKit.color(this, cached == null ? R.color.text_secondary : R.color.ok));
        bottom.addView(latency);
        row.addView(bottom);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = UiKit.dp(this, 10);
        row.setLayoutParams(params);

        row.setOnClickListener(v -> {
            store.select(index);
            refreshNodes();
            refreshConnectCard();
            switchTab(0);
            if (proxy.state() == ProxyManager.State.CONNECTED) {
                toast("已切换节点，重新连接后生效");
            }
        });
        row.setOnLongClickListener(v -> {
            showNodeActions(index);
            return true;
        });
        return row;
    }

    private void showNodeActions(int index) {
        SocksNode node = store.nodes().get(index);
        String[] items = {
                getString(R.string.node_edit),
                getString(R.string.node_test),
                getString(R.string.node_delete),
        };
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle(node.displayName())
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        showNodeDialog(index, node);
                    } else if (which == 1) {
                        latencyByEndpoint.remove(node.endpoint());
                        LatencyTester.test(node, ms -> {
                            if (ms < 0) {
                                toast(getString(R.string.err_node_unreachable));
                            } else {
                                latencyByEndpoint.put(node.endpoint(), ms);
                            }
                            refreshNodes();
                            refreshConnectCard();
                        });
                        toast(getString(R.string.latency_testing));
                    } else {
                        store.delete(index);
                        refreshNodes();
                        refreshConnectCard();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ───────────────────────── 设置页 ─────────────────────────

    private LinearLayout buildSettingsPage() {
        LinearLayout page = column();
        ScrollView scroll = new ScrollView(this);
        page.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout body = column();
        UiKit.setPadding(body, 20, 14, 20, 24);
        scroll.addView(body);

        TextView title = UiKit.text(this, getString(R.string.settings_title), 22f,
                UiKit.color(this, R.color.text_primary));
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        body.addView(title);

        TextView modeTitle = UiKit.text(this, getString(R.string.settings_mode), 13f,
                UiKit.color(this, R.color.text_secondary));
        modeTitle.setPadding(0, UiKit.dp(this, 20), 0, UiKit.dp(this, 8));
        body.addView(modeTitle);

        modeSmart = buildModeOption(getString(R.string.mode_smart),
                getString(R.string.mode_smart_desc));
        modeGlobal = buildModeOption(getString(R.string.mode_global),
                getString(R.string.mode_global_desc));
        modeSmart.setOnClickListener(v -> setMode(false));
        modeGlobal.setOnClickListener(v -> setMode(true));

        LinearLayout.LayoutParams optionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        body.addView(modeSmart, optionParams);
        LinearLayout.LayoutParams optionParams2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        optionParams2.topMargin = UiKit.dp(this, 10);
        body.addView(modeGlobal, optionParams2);

        smartDesc = (TextView) modeSmart.getTag();
        globalDesc = (TextView) modeGlobal.getTag();

        body.addView(buildAutoRow());
        body.addView(buildProbeCard());
        body.addView(buildCompatibilityCard());
        body.addView(buildLogRow());
        body.addView(buildAboutCard());

        return page;
    }

    private LinearLayout buildModeOption(String name, String desc) {
        LinearLayout option = column();
        option.setBackgroundResource(R.drawable.bg_option);
        UiKit.setPadding(option, 16, 14, 16, 14);
        option.setClickable(true);

        TextView title = UiKit.text(this, name, 15f, UiKit.color(this, R.color.text_primary));
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        option.addView(title);

        TextView sub = UiKit.text(this, desc, 11f, UiKit.color(this, R.color.text_secondary));
        sub.setPadding(0, UiKit.dp(this, 4), 0, 0);
        option.addView(sub);

        option.setTag(sub);
        return option;
    }

    private LinearLayout buildAutoRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_card);
        UiKit.setPadding(row, 16, 14, 16, 14);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = UiKit.dp(this, 18);
        row.setLayoutParams(params);

        LinearLayout texts = column();
        TextView title = UiKit.text(this, getString(R.string.settings_boot), 14f,
                UiKit.color(this, R.color.text_primary));
        TextView sub = UiKit.text(this, getString(R.string.settings_boot_desc), 11f,
                UiKit.color(this, R.color.text_secondary));
        sub.setPadding(0, UiKit.dp(this, 3), 0, 0);
        texts.addView(title);
        texts.addView(sub);
        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        autoSwitch = new Switch(this);
        int brand = UiKit.color(this, R.color.brand);
        autoSwitch.setThumbTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{brand, UiKit.color(this, R.color.idle)}));
        autoSwitch.setTrackTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{0x664C7DFF, 0x339AA3B2}));
        autoSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (!suppressAutoSave) {
                store.setAutoConnect(checked);
            }
        });
        row.addView(autoSwitch);
        return row;
    }

    private LinearLayout buildProbeCard() {
        LinearLayout card = column();
        card.setBackgroundResource(R.drawable.bg_card);
        UiKit.setPadding(card, 16, 14, 16, 14);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = UiKit.dp(this, 18);
        card.setLayoutParams(params);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = UiKit.text(this, getString(R.string.probe_title), 13f,
                UiKit.color(this, R.color.text_secondary));
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView action = UiKit.button(this, getString(R.string.probe_run), false);
        action.setOnClickListener(v -> runProbe());
        header.addView(action);
        card.addView(header);

        probeText = UiKit.text(this, getString(R.string.probe_idle), 11f,
                UiKit.color(this, R.color.text_secondary));
        probeText.setPadding(0, UiKit.dp(this, 8), 0, 0);
        card.addView(probeText);

        return card;
    }

    private void runProbe() {
        SocksNode node = store.current();
        if (node == null) {
            toast(getString(R.string.hint_no_node));
            return;
        }
        probeText.setText(getString(R.string.probe_running));
        probeRunning = Boolean.TRUE;
        NetProbe.run(node, (nodeResult, dnsResult, tunnelResult) -> {
            probeRunning = null;
            probeText.setText(formatProbe(nodeResult, dnsResult, tunnelResult));
            logProbe(nodeResult, dnsResult, tunnelResult);
        });
    }

    private String formatProbe(NetProbe.Result node, NetProbe.Result dns, NetProbe.Result tunnel) {
        return getString(R.string.probe_node) + "：" + node.detail + "\n"
                + getString(R.string.probe_dns) + "：" + dns.detail + "\n"
                + getString(R.string.probe_tunnel) + "：" + tunnel.detail;
    }

    private void logProbe(NetProbe.Result node, NetProbe.Result dns, NetProbe.Result tunnel) {
        LogRecorder.step("自检 节点 → " + node.detail);
        LogRecorder.step("自检 DNS → " + dns.detail);
        LogRecorder.step("自检 隧道 → " + tunnel.detail);
    }

    private LinearLayout buildCompatibilityCard() {
        LinearLayout card = column();
        card.setBackgroundResource(R.drawable.bg_card);
        UiKit.setPadding(card, 16, 14, 16, 14);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = UiKit.dp(this, 18);
        card.setLayoutParams(params);

        TextView title = UiKit.text(this, getString(R.string.settings_compat), 13f,
                UiKit.color(this, R.color.text_secondary));
        card.addView(title);

        TextView hint = UiKit.text(this, getString(R.string.settings_compat_desc), 11f,
                UiKit.color(this, R.color.text_secondary));
        hint.setPadding(0, UiKit.dp(this, 4), 0, UiKit.dp(this, 10));
        card.addView(hint);

        LinearLayout stacks = new LinearLayout(this);
        stacks.setOrientation(LinearLayout.HORIZONTAL);
        stackGvisor = buildStackChip(getString(R.string.stack_gvisor));
        stackSystem = buildStackChip(getString(R.string.stack_system));
        stackGvisor.setOnClickListener(v -> setStack(CoreOptions.STACK_GVISOR));
        stackSystem.setOnClickListener(v -> setStack(CoreOptions.STACK_SYSTEM));
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        right.leftMargin = UiKit.dp(this, 10);
        stacks.addView(stackGvisor, left);
        stacks.addView(stackSystem, right);
        card.addView(stacks);

        stackDesc = UiKit.text(this, "", 11f, UiKit.color(this, R.color.text_secondary));
        stackDesc.setPadding(0, UiKit.dp(this, 8), 0, 0);
        card.addView(stackDesc);

        TextView dnsLabel = UiKit.text(this, getString(R.string.settings_dns), 14f,
                UiKit.color(this, R.color.text_primary));
        dnsLabel.setPadding(0, UiKit.dp(this, 14), 0, UiKit.dp(this, 8));
        card.addView(dnsLabel);

        LinearLayout dnsRow = new LinearLayout(this);
        dnsRow.setOrientation(LinearLayout.HORIZONTAL);
        dnsRemote = buildStackChip(getString(R.string.dns_remote));
        dnsDomestic = buildStackChip(getString(R.string.dns_domestic));
        dnsRemote.setOnClickListener(v -> setDns(CoreOptions.DNS_REMOTE));
        dnsDomestic.setOnClickListener(v -> setDns(CoreOptions.DNS_DOMESTIC));
        LinearLayout.LayoutParams dnsLeft = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout.LayoutParams dnsRight = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        dnsRight.leftMargin = UiKit.dp(this, 10);
        dnsRow.addView(dnsRemote, dnsLeft);
        dnsRow.addView(dnsDomestic, dnsRight);
        card.addView(dnsRow);

        dnsDesc = UiKit.text(this, "", 11f, UiKit.color(this, R.color.text_secondary));
        dnsDesc.setPadding(0, UiKit.dp(this, 8), 0, 0);
        card.addView(dnsDesc);

        LinearLayout followRow = new LinearLayout(this);
        followRow.setOrientation(LinearLayout.HORIZONTAL);
        followRow.setGravity(Gravity.CENTER_VERTICAL);
        followRow.setPadding(0, UiKit.dp(this, 14), 0, 0);

        LinearLayout followTexts = column();
        TextView followTitle = UiKit.text(this, getString(R.string.settings_follow), 14f,
                UiKit.color(this, R.color.text_primary));
        TextView followSub = UiKit.text(this, getString(R.string.settings_follow_desc), 11f,
                UiKit.color(this, R.color.text_secondary));
        followSub.setPadding(0, UiKit.dp(this, 3), 0, 0);
        followTexts.addView(followTitle);
        followTexts.addView(followSub);
        followRow.addView(followTexts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        followSwitch = buildSwitch();
        followSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (!suppressAutoSave) {
                store.setFollowInterface(checked);
            }
        });
        followRow.addView(followSwitch);
        card.addView(followRow);

        return card;
    }

    private TextView buildStackChip(String label) {
        TextView chip = UiKit.text(this, label, 13f, UiKit.color(this, R.color.text_primary));
        chip.setBackgroundResource(R.drawable.bg_option);
        UiKit.setPadding(chip, 12, 10, 12, 10);
        chip.setGravity(Gravity.CENTER);
        chip.setClickable(true);
        return chip;
    }

    private Switch buildSwitch() {
        Switch view = new Switch(this);
        int brand = UiKit.color(this, R.color.brand);
        view.setThumbTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{brand, UiKit.color(this, R.color.idle)}));
        view.setTrackTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{0x664C7DFF, 0x339AA3B2}));
        return view;
    }

    private void setStack(String stack) {
        store.setStack(stack);
        refreshSettings();
        if (proxy.state() == ProxyManager.State.CONNECTED) {
            toast(getString(R.string.reconnect_to_apply));
        }
    }

    private void setDns(String mode) {
        store.setDnsMode(mode);
        refreshSettings();
        if (proxy.state() == ProxyManager.State.CONNECTED) {
            toast(getString(R.string.reconnect_to_apply));
        }
    }

    private LinearLayout buildLogRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_card);
        UiKit.setPadding(row, 16, 16, 16, 16);
        row.setClickable(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = UiKit.dp(this, 10);
        row.setLayoutParams(params);

        TextView title = UiKit.text(this, getString(R.string.settings_log), 14f,
                UiKit.color(this, R.color.text_primary));
        row.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView arrow = UiKit.icon(this, R.drawable.ic_chevron, 16);
        UiKit.tint(arrow, UiKit.color(this, R.color.text_secondary));
        row.addView(arrow);

        row.setOnClickListener(v -> showLogDialog());
        return row;
    }

    private LinearLayout buildAboutCard() {
        LinearLayout card = column();
        card.setBackgroundResource(R.drawable.bg_card);
        UiKit.setPadding(card, 16, 14, 16, 14);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = UiKit.dp(this, 18);
        card.setLayoutParams(params);

        TextView title = UiKit.text(this,
                getString(R.string.settings_version) + " " + BuildConfig.VERSION_NAME,
                14f, UiKit.color(this, R.color.text_primary));
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        card.addView(title);

        TextView desc = UiKit.text(this, getString(R.string.settings_about_desc), 11f,
                UiKit.color(this, R.color.text_secondary));
        desc.setPadding(0, UiKit.dp(this, 4), 0, 0);
        card.addView(desc);
        return card;
    }

    private void setMode(boolean global) {
        store.setGlobalMode(global);
        refreshSettings();
        if (proxy.state() == ProxyManager.State.CONNECTED) {
            toast("分流模式已修改，重新连接后生效");
        }
    }

    private void refreshSettings() {
        if (modeSmart == null) {
            return;
        }
        boolean global = store.globalMode();
        modeSmart.setSelected(!global);
        modeGlobal.setSelected(global);
        smartDesc.setTextColor(UiKit.color(this, global ? R.color.text_secondary : R.color.brand));
        globalDesc.setTextColor(UiKit.color(this, global ? R.color.brand : R.color.text_secondary));
        suppressAutoSave = true;
        autoSwitch.setChecked(store.autoConnect());
        suppressAutoSave = false;

        if (stackGvisor != null) {
            boolean system = CoreOptions.STACK_SYSTEM.equals(store.stack());
            stackGvisor.setSelected(!system);
            stackSystem.setSelected(system);
            stackDesc.setText(system ? R.string.stack_system_desc : R.string.stack_gvisor_desc);
            suppressAutoSave = true;
            followSwitch.setChecked(store.followInterface());
            suppressAutoSave = false;

            boolean domestic = CoreOptions.DNS_DOMESTIC.equals(store.dnsMode());
            dnsRemote.setSelected(!domestic);
            dnsDomestic.setSelected(domestic);
            dnsDesc.setText(domestic ? R.string.dns_domestic_desc : R.string.dns_remote_desc);
        }
    }

    // ───────────────────────── 状态刷新 ─────────────────────────

    private void refreshAll() {
        refreshConnectCard();
        refreshNodes();
        refreshSettings();
        updateStatus();
        if (proxy.state() == ProxyManager.State.CONNECTED) {
            ticker.removeCallbacks(tick);
            ticker.post(tick);
        } else {
            ticker.removeCallbacks(tick);
            updateDuration();
        }
    }

    private void refreshConnectCard() {
        if (nodeName == null) {
            return;
        }
        SocksNode node = store.current();
        if (node == null) {
            nodeName.setText(getString(R.string.nodes_empty));
            nodeEndpoint.setText(getString(R.string.hint_no_node));
            nodeLatency.setText(getString(R.string.latency_none));
            return;
        }
        nodeName.setText(node.displayName());
        nodeEndpoint.setText(node.toLink());
        Integer cached = latencyByEndpoint.get(node.endpoint());
        nodeLatency.setText(cached == null ? getString(R.string.latency_none)
                : getString(R.string.latency_ms, cached));
    }

    private void updateStatus() {
        ProxyManager.State state = proxy.state();
        if (statusChip == null) {
            return;
        }
        int text;
        int color;
        switch (state) {
            case CONNECTED:
                text = R.string.state_connected;
                color = R.color.ok;
                break;
            case CONNECTING:
                text = R.string.state_connecting;
                color = R.color.warn;
                break;
            case DISCONNECTING:
                text = R.string.state_disconnecting;
                color = R.color.warn;
                break;
            default:
                text = R.string.state_disconnected;
                color = R.color.text_secondary;
                break;
        }
        statusChip.setText(getString(text));
        statusChip.setTextColor(UiKit.color(this, color));
        circle.setState(state);
        if (state == ProxyManager.State.CONNECTED) {
            ticker.removeCallbacks(tick);
            ticker.post(tick);
        } else {
            ticker.removeCallbacks(tick);
        }
        updateDuration();
    }

    private void updateDuration() {
        if (nodeLatency == null) {
            return;
        }
        ProxyManager.State state = proxy.state();
        String latencyText;
        if (state == ProxyManager.State.CONNECTED && proxy.connectedAt() > 0) {
            long seconds = (System.currentTimeMillis() - proxy.connectedAt()) / 1000;
            latencyText = String.format(Locale.CHINA, "%02d:%02d:%02d",
                    seconds / 3600, (seconds % 3600) / 60, seconds % 60);
        } else {
            SocksNode node = store.current();
            Integer cached = node == null ? null : latencyByEndpoint.get(node.endpoint());
            latencyText = cached == null ? getString(R.string.latency_none)
                    : getString(R.string.latency_ms, cached);
        }
        nodeLatency.setText(latencyText);

        if (probeText != null && probeRunning == null && !proxy.lastProbe().isEmpty()
                && state == ProxyManager.State.CONNECTED) {
            probeText.setText(proxy.lastProbe());
        }
    }

    @Override
    public void onStateChanged(ProxyManager.State state, String message) {
        updateStatus();
        refreshConnectCard();
        if (message != null && !message.isEmpty()) {
            toast(message);
        }
    }

    // ───────────────────────── 交互 ─────────────────────────

    private void onCircleClicked() {
        try {
            ProxyManager.State state = proxy.state();
            if (state == ProxyManager.State.CONNECTED || state == ProxyManager.State.CONNECTING) {
                proxy.disconnect(this);
                return;
            }
            if (store.current() == null) {
                toast(getString(R.string.hint_no_node));
                switchTab(1);
                return;
            }
            requestNotificationPermission();
            Intent prepare = VpnService.prepare(this);
            if (prepare != null) {
                startActivityForResult(prepare, REQ_VPN);
            } else {
                proxy.connect(this, store.current());
            }
        } catch (Throwable t) {
            LogRecorder.error("连接", "点击处理失败", t);
            toast(getString(R.string.err_core_start));
        }
    }

    /** 上次启动时的崩溃记录，有就直接摆出来，用户可一键复制 */
    private void showCrashReportIfAny() {
        String report = CrashGuard.lastReport(this);
        if (report.isEmpty()) {
            return;
        }
        LogRecorder.log("启动", "发现上次崩溃记录");
        TextView view = new TextView(this);
        view.setTextSize(10f);
        view.setTextColor(UiKit.color(this, R.color.text_primary));
        view.setTypeface(Typeface.MONOSPACE);
        view.setText(report);
        UiKit.setPadding(view, 16, 12, 16, 12);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(view);

        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle(R.string.crash_title)
                .setView(scroll)
                .setPositiveButton(R.string.close, (dialog, which) -> CrashGuard.clear(this))
                .setNeutralButton(R.string.log_copy, (dialog, which) -> copyLog(report))
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) {
                proxy.connect(this, store.current());
            } else {
                toast(getString(R.string.err_vpn_denied));
            }
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIFICATION);
        }
    }

    // ───────────────────────── 对话框 ─────────────────────────

    private void showNodeDialog(int editIndex, SocksNode preset) {
        SocksNode node = preset;
        if (node == null && editIndex >= 0) {
            node = store.nodes().get(editIndex);
        }
        SocksNode initial = node == null ? new SocksNode() : node;

        LinearLayout form = column();
        UiKit.setPadding(form, 20, 8, 20, 4);

        EditText name = input(getString(R.string.node_name), InputType.TYPE_CLASS_TEXT);
        name.setText(initial.name);
        form.addView(name, fieldParams());

        EditText host = input(getString(R.string.node_host), InputType.TYPE_CLASS_TEXT);
        host.setText(initial.host);
        form.addView(host, fieldParams());

        EditText port = input(getString(R.string.node_port), InputType.TYPE_CLASS_NUMBER);
        port.setText(String.valueOf(initial.port));
        form.addView(port, fieldParams());

        EditText user = input(getString(R.string.node_user), InputType.TYPE_CLASS_TEXT);
        user.setText(initial.username);
        form.addView(user, fieldParams());

        EditText pass = input(getString(R.string.node_pass),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pass.setText(initial.password);
        form.addView(pass, fieldParams());

        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle(editIndex >= 0 ? R.string.node_edit : R.string.node_new)
                .setView(form)
                .setPositiveButton(R.string.node_save, (dialog, which) -> {
                    SocksNode result = new SocksNode();
                    result.name = name.getText().toString().trim();
                    result.host = host.getText().toString().trim();
                    try {
                        result.port = Integer.parseInt(port.getText().toString().trim());
                    } catch (Exception e) {
                        result.port = 0;
                    }
                    result.username = user.getText().toString().trim();
                    result.password = pass.getText().toString();
                    if (!result.isValid()) {
                        toast(getString(R.string.err_node_invalid));
                        return;
                    }
                    store.saveNode(result, editIndex);
                    refreshNodes();
                    refreshConnectCard();
                })
                .setNegativeButton(R.string.cancel, null);

        if (editIndex >= 0) {
            builder.setNeutralButton(R.string.node_delete, (dialog, which) -> {
                store.delete(editIndex);
                refreshNodes();
                refreshConnectCard();
            });
        }
        builder.show();
    }

    private void showImportDialog() {
        LinearLayout form = column();
        UiKit.setPadding(form, 20, 10, 20, 4);
        EditText link = input("socks5://用户名:密码@服务器:端口", InputType.TYPE_CLASS_TEXT);
        link.setSingleLine(false);
        link.setMinLines(2);
        form.addView(link, fieldParams());

        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle(R.string.node_import)
                .setView(form)
                .setPositiveButton(R.string.confirm, (dialog, which) -> {
                    SocksNode node = SocksNode.parseLink(link.getText().toString());
                    if (node == null) {
                        toast(getString(R.string.err_link_invalid));
                        return;
                    }
                    showNodeDialog(-1, node);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showLogDialog() {
        String text = LogRecorder.dump();
        TextView view = new TextView(this);
        view.setTextSize(11f);
        view.setTextColor(UiKit.color(this, R.color.text_primary));
        view.setTypeface(Typeface.MONOSPACE);
        view.setText(text.isEmpty() ? getString(R.string.log_empty) : text);
        UiKit.setPadding(view, 16, 12, 16, 12);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(view);

        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle(R.string.log_title)
                .setView(scroll)
                .setPositiveButton(R.string.close, null)
                .setNeutralButton(R.string.log_copy, (dialog, which) -> copyLog(text))
                .setNegativeButton(R.string.log_clear, (dialog, which) -> LogRecorder.clear())
                .show();
    }

    private void copyLog(String text) {
        ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager == null) {
            return;
        }
        manager.setPrimaryClip(ClipData.newPlainText("SK5", text));
        toast(getString(R.string.log_copied));
    }

    private EditText input(String hint, int inputType) {
        EditText edit = new EditText(this);
        edit.setHint(hint);
        edit.setHintTextColor(UiKit.color(this, R.color.text_secondary));
        edit.setTextColor(UiKit.color(this, R.color.text_primary));
        edit.setTextSize(14f);
        edit.setInputType(inputType);
        edit.setBackgroundResource(R.drawable.bg_input);
        UiKit.setPadding(edit, 14, 11, 14, 11);
        return edit;
    }

    private LinearLayout.LayoutParams fieldParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = UiKit.dp(this, 10);
        return params;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
