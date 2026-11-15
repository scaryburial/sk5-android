package com.leidian.sk5;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.android.controller.ActivityController;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class MainActivityTest {

    @Test
    public void launchesWithoutCrash() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        Activity activity = controller.get();
        assertNotNull(activity);
        assertTrue(!activity.isFinishing());
    }

    @Test
    public void startsDisconnected() {
        assertEquals(ProxyManager.State.DISCONNECTED, ProxyManager.get().state());
    }

    @Test
    public void nodeStorePersistsNode() {
        Context context = ApplicationProvider.getApplicationContext();
        NodeStore store = NodeStore.get(context);
        int before = store.size();

        SocksNode node = new SocksNode();
        node.name = "测试节点";
        node.host = "198.51.100.7";
        node.port = 1080;
        store.saveNode(node, -1);

        assertEquals(before + 1, store.size());
        assertEquals("测试节点", store.current().displayName());
        assertEquals("198.51.100.7", store.current().host);

        store.delete(store.currentIndex());
        assertEquals(before, store.size());
    }

    @Test
    public void modeToggleIsPersisted() {
        Context context = ApplicationProvider.getApplicationContext();
        NodeStore store = NodeStore.get(context);
        store.setGlobalMode(true);
        assertTrue(NodeStore.get(context).globalMode());
        store.setGlobalMode(false);
        assertFalse(NodeStore.get(context).globalMode());
    }
}
