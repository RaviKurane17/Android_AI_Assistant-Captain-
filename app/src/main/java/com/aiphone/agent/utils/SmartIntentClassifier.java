package com.aiphone.agent.utils;

import android.content.Context;
import android.util.Log;

import com.google.mlkit.nl.entityextraction.Entity;
import com.google.mlkit.nl.entityextraction.EntityAnnotation;
import com.google.mlkit.nl.entityextraction.EntityExtraction;
import com.google.mlkit.nl.entityextraction.EntityExtractionParams;
import com.google.mlkit.nl.entityextraction.EntityExtractor;
import com.google.mlkit.nl.entityextraction.EntityExtractorOptions;
import com.google.mlkit.nl.entityextraction.DateTimeEntity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class SmartIntentClassifier {
    private static final String TAG = "SmartIntentClassifier";

    // ML Kit Extractor
    private static EntityExtractor entityExtractor;

    public static final String INTENT_TORCH_ON = "torch_on";
    public static final String INTENT_TORCH_OFF = "torch_off";
    public static final String INTENT_ALARM_SET = "alarm_set";
    public static final String INTENT_WIFI_ON = "wifi_on";
    public static final String INTENT_WIFI_OFF = "wifi_off";
    public static final String INTENT_TIMER_SET = "timer_set";
    public static final String INTENT_UNKNOWN = "unknown";

    static class TrainingData {
        String intent;
        List<String> phrases;
        TrainingData(String intent, String... phrases) {
            this.intent = intent;
            this.phrases = Arrays.asList(phrases);
        }
    }

    private static final List<TrainingData> trainingSet = new ArrayList<>();

    static {
        trainingSet.add(new TrainingData(INTENT_TORCH_ON,
                "turn on the torch", "flashlight on", "torch jalao", "light on kar do",
                "andhera hai light jala de", "kuch dikhai nahi de raha", "torch on"
        ));
        trainingSet.add(new TrainingData(INTENT_TORCH_OFF,
                "turn off the torch", "flashlight off", "torch band karo", "light band kar do",
                "ab andhera nahi hai", "torch off"
        ));
        trainingSet.add(new TrainingData(INTENT_ALARM_SET,
                "set an alarm", "alarm lagao", "wake me up", "mujhe utha dena",
                "set alarm", "alarm for tomorrow", "kal subah utha dena", "remind me at"
        ));
        trainingSet.add(new TrainingData(INTENT_TIMER_SET,
                "set timer", "set reminder", "set a timer", "remind me in", "start timer"
        ));
        trainingSet.add(new TrainingData(INTENT_WIFI_ON,
                "turn on wifi", "wifi on", "internet chalu karo", "enable wifi"
        ));
        trainingSet.add(new TrainingData(INTENT_WIFI_OFF,
                "turn off wifi", "wifi off", "internet band karo", "disable wifi"
        ));
    }

    public static void init(Context context) {
        if (entityExtractor == null) {
            entityExtractor = EntityExtraction.getClient(
                    new EntityExtractorOptions.Builder(EntityExtractorOptions.ENGLISH).build());
            entityExtractor.downloadModelIfNeeded().addOnSuccessListener(aVoid -> {
                Log.d(TAG, "ML Kit Entity Extraction model downloaded & ready.");
            }).addOnFailureListener(e -> {
                Log.e(TAG, "ML Kit Entity Extraction download failed.", e);
            });
        }
    }

    public static String classifyIntent(String input) {
        input = input.toLowerCase().trim();
        String bestIntent = INTENT_UNKNOWN;
        double maxScore = 0.0;

        Map<String, Integer> inputVec = getWordVector(input);

        for (TrainingData td : trainingSet) {
            for (String phrase : td.phrases) {
                Map<String, Integer> phraseVec = getWordVector(phrase.toLowerCase());
                double score = cosineSimilarity(inputVec, phraseVec);
                if (score > maxScore) {
                    maxScore = score;
                    bestIntent = td.intent;
                }
            }
        }
        
        // Threshold: 0.35 means significant word overlap
        return maxScore > 0.35 ? bestIntent : INTENT_UNKNOWN;
    }

    private static Map<String, Integer> getWordVector(String text) {
        Map<String, Integer> vec = new HashMap<>();
        String[] words = text.split("\\W+");
        for (String w : words) {
            if (w.isEmpty()) continue;
            vec.put(w, vec.getOrDefault(w, 0) + 1);
        }
        return vec;
    }

    private static double cosineSimilarity(Map<String, Integer> v1, Map<String, Integer> v2) {
        Set<String> allWords = new HashSet<>(v1.keySet());
        allWords.addAll(v2.keySet());

        double dotProduct = 0;
        double norm1 = 0;
        double norm2 = 0;

        for (String word : allWords) {
            int f1 = v1.getOrDefault(word, 0);
            int f2 = v2.getOrDefault(word, 0);

            dotProduct += f1 * f2;
            norm1 += Math.pow(f1, 2);
            norm2 += Math.pow(f2, 2);
        }

        if (norm1 == 0 || norm2 == 0) return 0.0;
        return dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }

    public static List<EntityAnnotation> extractEntitiesSync(String text) {
        if (entityExtractor == null) return new ArrayList<>();
        
        final List<EntityAnnotation> result = new ArrayList<>();
        final CountDownLatch latch = new CountDownLatch(1);

        EntityExtractionParams params = new EntityExtractionParams.Builder(text).build();
        entityExtractor.annotate(params)
                .addOnSuccessListener(annotations -> {
                    result.addAll(annotations);
                    latch.countDown();
                })
                .addOnFailureListener(e -> {
                    latch.countDown();
                });

        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        
        return result;
    }
}
