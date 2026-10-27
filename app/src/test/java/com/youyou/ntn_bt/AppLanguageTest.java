package com.youyou.ntn_bt;
import org.junit.Test;
import static org.junit.Assert.*;
public class AppLanguageTest {
    @Test public void systemAndUnknown() { assertEquals(0, AppLanguage.index("")); assertEquals(0, AppLanguage.index("fr-FR")); }
    @Test public void chineseScriptAndRegion() {
        assertEquals(1, AppLanguage.index("zh-CN")); assertEquals(1, AppLanguage.index("zh-Hans"));
        assertEquals(3, AppLanguage.index("zh-Hant")); assertEquals(3, AppLanguage.index("zh-TW"));
        assertEquals(3, AppLanguage.index("zh-HK,en-US"));
    }
    @Test public void supportedLanguages() {
        assertEquals(2, AppLanguage.index("en-US")); assertEquals(4, AppLanguage.index("ja-JP"));
        assertEquals(5, AppLanguage.index("es-MX"));
        for (int i=1;i<AppLanguage.TAGS.length;i++) assertEquals(i,AppLanguage.index(AppLanguage.TAGS[i]));
    }
}
