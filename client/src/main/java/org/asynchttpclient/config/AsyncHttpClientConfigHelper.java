package org.asynchttpclient.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class AsyncHttpClientConfigHelper {

    private static volatile Config config;

    public static Config getAsyncHttpClientConfig() {
        if (config == null) {
            config = new Config();
        }

        return config;
    }

    /**
     * This method invalidates the property caches. So if a system property has
     * been changed and the effect of this change is to be seen then call
     * reloadProperties() and then getAsyncHttpClientConfig() to get the new
     * property values.
     */
    public static void reloadProperties() {
        if (config != null)
            config.reload();
    }

    public static class Config {

        public static final String DEFAULT_AHC_PROPERTIES = "ahc-default.properties";
        public static final String CUSTOM_AHC_PROPERTIES = "ahc.properties";

        private final ConcurrentHashMap<String, String> propsCache = new ConcurrentHashMap<String, String>();
        private final Properties defaultProperties = parsePropertiesFile(DEFAULT_AHC_PROPERTIES);
        private volatile Properties customProperties = parsePropertiesFile(CUSTOM_AHC_PROPERTIES);

        public void reload() {
            customProperties = parsePropertiesFile(CUSTOM_AHC_PROPERTIES);
            propsCache.clear();
        }

        private Properties parsePropertiesFile(String file) {
            Properties props = new Properties();
            InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(file);
            try {
                if (is != null) {
                    props.load(is);
                } else {
                   //Try loading from this class classloader instead, e.g. for OSGi environments.
                    InputStream is2 = this.getClass().getClassLoader().getResourceAsStream(file);
                    try {
                        if (is2 != null) {
                            props.load(is2);
                        }
                    } finally {
                        if(null != is2)
                        is2.close();
                    }
                }
            } catch (IOException e) {
                throw new IllegalArgumentException("Can't parse file", e);
            } finally {
                try {
                    if(null != is)
                    is.close();
                } catch (IOException e) {
                   //
                }
            }
            return props;
        }

        public String getString(String key) {
            return propsCache.computeIfAbsent(key, new Function<String, String>() {

                @Override
                public String apply(String key) {
                    String value = System.getProperty(key);
                    if (value == null)
                        value = customProperties.getProperty(key);
                    if (value == null)
                        value = defaultProperties.getProperty(key);
                    return value;
                }
            });
        }

        public String[] getStringArray(String key) {
            String s = getString(key);
            String[] rawArray = s.split(",");
            String[] array = new String[rawArray.length];
            for (int i = 0; i < rawArray.length; i++)
                array[i] = rawArray[i].trim();
            return array;
        }

        public int getInt(String key) {
            return Integer.parseInt(getString(key));
        }

        public long getLong(String key) {
            return Long.parseLong(getString(key));
        }

        public Integer getInteger(String key) {
            String s = getString(key);
            return s != null ? Integer.valueOf(s) : null;
        }

        public boolean getBoolean(String key) {
            return Boolean.parseBoolean(getString(key));
        }
    }
}
