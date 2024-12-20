package com.facebook.plugin.arrow;

import com.facebook.airlift.json.JsonModule;

public class TestingArrowFlightPlugin
        extends ArrowPlugin
{
    public TestingArrowFlightPlugin()
    {
        super("arrow", new TestingArrowModule(), new JsonModule());
    }
}
