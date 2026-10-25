package com.nhkdaily;

import com.atilika.kuromoji.ipadic.Token;
import com.atilika.kuromoji.ipadic.Tokenizer;

import java.util.ArrayList;
import java.util.List;

/**
 * Japanese word splitting with readings, on the phone, no internet:
 * Kuromoji (Apache 2.0, github.com/atilika/kuromoji) with the IPADIC dictionary.
 * Loading the dictionary takes a second or two, so it's done once, in the
 * background, and kept for as long as the app is running.
 */
final class KuromojiAnalyzer implements Furigana.Analyzer {

    private static KuromojiAnalyzer instance;
    private final Tokenizer tokenizer;

    private KuromojiAnalyzer() {
        tokenizer = new Tokenizer();
    }

    static synchronized KuromojiAnalyzer get() {
        if (instance == null) instance = new KuromojiAnalyzer();
        return instance;
    }

    @Override
    public synchronized List<String[]> tokenize(String text) {
        List<String[]> out = new ArrayList<>();
        for (Token t : tokenizer.tokenize(text)) {
            out.add(new String[]{t.getSurface(), t.getReading()});
        }
        return out;
    }
}
