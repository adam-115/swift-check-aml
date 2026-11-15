package com.swiftcheck.aml.plugins.depep.landgovernments;

final class Html {

    private Html() {
    }

    static String text(String html) {
        if (html == null) return "";
        return html
                .replaceAll("(?si)<script[^>]*>.*?</script>", "")
                .replaceAll("(?si)<style[^>]*>.*?</style>", "")
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&#x007C;", "|")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replaceAll("\\s+", " ")
                .trim();
    }
}

