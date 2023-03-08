/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.astraea.common;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.management.InstanceNotFoundException;
import org.astraea.common.cost.CostFunction;

public final class Utils {

  // ---------------------[Duration]---------------------//

  public static final Pattern TIME_PATTERN =
      Pattern.compile("^(?<value>[0-9]+)(?<unit>days|day|h|m|s|ms|us|ns|)$");

  /**
   * A converter for time unit.
   *
   * <p>This converter is able to transform following time string into corresponding {@link
   * Duration}.
   *
   * <ul>
   *   <li>{@code "30s"} to {@code Duration.ofSeconds(30)}
   *   <li>{@code "1m"} to {@code Duration.ofMinutes(1)}
   *   <li>{@code "24h"} to {@code Duration.ofHours(24)}
   *   <li>{@code "7day"} to {@code Duration.ofDays(7)}
   *   <li>{@code "7days"} to {@code Duration.ofDays(7)}
   *   <li>{@code "350ms"} to {@code Duration.ofMillis(350)}
   *   <li>{@code "123us"} to {@code Duration.ofNanos(123 * 1000)}
   *   <li>{@code "100ns"} to {@code Duration.ofNanos(100)}
   * </ul>
   *
   * If no unit specified, second unit will be used:
   *
   * <ul>
   *   <li>{@code "1"} to {@code Duration.ofSeconds(1)}
   *   <li>{@code "0"} to {@code Duration.ofSeconds(0)}
   * </ul>
   *
   * Currently, negative time is not supported. So the following example doesn't work.
   *
   * <ul>
   *   <li><b>(doesn't work)</b> {@code "-1" to {@code Duration.ofSeconds(-1)}}
   * </ul>
   *
   * Currently, floating value time is not supported. So the following example doesn't work.
   *
   * <ul>
   *   <li><b>(doesn't work)</b> {@code "0.5" to {@code Duration.ofMillis(500)}}
   * </ul>
   */
  public static Duration toDuration(String input) {
    Matcher matcher = TIME_PATTERN.matcher(input);
    if (matcher.find()) {
      long value = Long.parseLong(matcher.group("value"));
      String unit = matcher.group("unit");
      switch (unit) {
        case "days":
        case "day":
          return Duration.ofDays(value);
        case "h":
          return Duration.ofHours(value);
        case "m":
          return Duration.ofMinutes(value);
        case "ms":
          return Duration.ofMillis(value);
        case "us":
          return Duration.ofNanos(value * 1000);
        case "ns":
          return Duration.ofNanos(value);
        case "s":
        default:
          return Duration.ofSeconds(value);
      }
    } else {
      throw new IllegalArgumentException("value \"" + input + "\" doesn't match any time format");
    }
  }

  /**
   * Convert the exception thrown by getter to RuntimeException, except ExecutionException.
   * ExecutionException will be converted to ExecutionRuntimeException , in order to preserve the
   * stacktrace of ExecutionException. This method can eliminate the exception from Java signature.
   *
   * @param getter to execute
   * @param <R> type of returned object
   * @return the object created by getter
   */
  public static <R> R packException(Getter<R> getter) {
    try {
      return getter.get();
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    } catch (InstanceNotFoundException e) {
      throw new NoSuchElementException(e.getMessage());
    } catch (RuntimeException exception) {
      throw exception;
    } catch (ExecutionException exception) {
      throw new ExecutionRuntimeException(exception);
    } catch (Throwable exception) {
      throw new RuntimeException(exception);
    }
  }

  /**
   * Convert the exception thrown by getter to RuntimeException. This method can eliminate the
   * exception from Java signature.
   *
   * @param runner to execute
   */
  public static void packException(Runner runner) {
    Utils.packException(
        () -> {
          runner.run();
          return null;
        });
  }

  /**
   * Swallow any exception caused by runner.
   *
   * @param runner to execute
   */
  public static void swallowException(Runner runner) {
    try {
      runner.run();
    } catch (Exception ex) {
      ex.printStackTrace();
    }
  }

  @SuppressWarnings("unchecked")
  public static <T> T construct(String path, Class<T> baseClass, Configuration configuration) {
    try {
      var clz = Class.forName(path);
      if (!baseClass.isAssignableFrom(clz))
        throw new IllegalArgumentException(
            path + " class is not sub class of " + baseClass.getName());
      return construct((Class<T>) clz, configuration);
    } catch (ClassNotFoundException e) {
      throw new IllegalArgumentException(e);
    }
  }

  public static <T extends CostFunction> Map<T, Double> costFunctions(
      Configuration config, Class<T> baseClass) {
    return config.entrySet().stream()
        .collect(
            Collectors.toUnmodifiableMap(
                entry -> construct(entry.getKey(), baseClass, config),
                entry -> {
                  try {
                    var weight = Double.parseDouble(entry.getValue());
                    if (weight < 0.0)
                      throw new IllegalArgumentException(
                          "the weight of cost function should be bigger than zero");
                    return weight;
                  } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                        "the weight of cost function must be positive number", e);
                  }
                }));
  }

  public static <T> T construct(Class<T> target, Configuration configuration) {
    try {
      // case 0: create the class by the given configuration
      var constructor = target.getConstructor(Configuration.class);
      constructor.setAccessible(true);
      return packException(() -> constructor.newInstance(configuration));
    } catch (NoSuchMethodException e) {
      // case 1: create the class by empty constructor
      return packException(
          () -> {
            var constructor = target.getConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
          });
    }
  }

  public interface Getter<R> {
    R get() throws Exception;
  }

  public interface Runner {
    void run() throws Exception;
  }

  public static String hostname() {
    return Utils.packException(() -> InetAddress.getLocalHost().getHostName());
  }

  /**
   * Wait for procedure. Default is 10 seconds
   *
   * @param done a flag indicating the result.
   */
  public static void waitFor(Supplier<Boolean> done) {
    waitFor(done, Duration.ofSeconds(10));
  }

  /**
   * Wait for procedure.
   *
   * @param done a flag indicating the result.
   */
  public static void waitFor(Supplier<Boolean> done, Duration timeout) {
    var endTime = System.currentTimeMillis() + timeout.toMillis();
    Exception lastError = null;
    while (System.currentTimeMillis() <= endTime)
      try {
        if (done.get()) return;
        Utils.sleep(Duration.ofSeconds(1));
      } catch (Exception e) {
        lastError = e;
      }
    if (lastError != null) throw new RuntimeException(lastError);
    throw new RuntimeException("Timeout to wait procedure");
  }

  public static int requirePositive(int value) {
    if (value <= 0)
      throw new IllegalArgumentException("the value: " + value + " must be bigger than zero");
    return value;
  }

  public static short requirePositive(short value) {
    if (value <= 0)
      throw new IllegalArgumentException("the value: " + value + " must be bigger than zero");
    return value;
  }

  /**
   * check the content of string
   *
   * @param value to check
   * @return input string if the string is not empty. Otherwise, it throws NPE or
   *     IllegalArgumentException
   */
  public static String requireNonEmpty(String value) {
    if (Objects.requireNonNull(value).isEmpty())
      throw new IllegalArgumentException("the value: " + value + " can't be empty");
    return value;
  }

  /**
   * Check if the time is expired.
   *
   * @param lastTime Check time.
   * @param interval Interval.
   * @return Is expired.
   */
  public static boolean isExpired(long lastTime, Duration interval) {
    return (lastTime + interval.toMillis()) < System.currentTimeMillis();
  }

  /**
   * Perform a sleep using the duration. InterruptedException is wrapped to RuntimeException.
   *
   * @param duration to sleep
   */
  public static void sleep(Duration duration) {
    Utils.swallowException(() -> TimeUnit.MILLISECONDS.sleep(duration.toMillis()));
  }

  public static String randomString(int len) {
    StringBuilder string = new StringBuilder(randomString());
    while (string.length() < len) {
      string.append(string).append(randomString());
    }
    return string.substring(0, len);
  }

  /**
   * a random string based on uuid without "-"
   *
   * @return random string
   */
  public static String randomString() {
    return java.util.UUID.randomUUID().toString().replaceAll("-", "");
  }

  public static Object staticMember(Class<?> clz, String attribute) {
    return reflectionAttribute(clz, null, attribute);
  }

  public static Object member(Object object, String attribute) {
    return reflectionAttribute(object.getClass(), object, attribute);
  }

  /**
   * reflection class attribute
   *
   * @param clz class type
   * @param object object. Null means static member
   * @param attribute attribute name
   * @return attribute
   */
  private static Object reflectionAttribute(Class<?> clz, Object object, String attribute) {
    do {
      try {
        var field = clz.getDeclaredField(attribute);
        field.setAccessible(true);
        return field.get(object);
      } catch (NoSuchFieldException e) {
        clz = clz.getSuperclass();
      } catch (IllegalAccessException e) {
        throw new RuntimeException(e);
      }
    } while (clz != null);
    throw new RuntimeException(attribute + " is not existent in " + object.getClass().getName());
  }

  public static List<String> constants(Class<?> clz, Predicate<String> variableNameFilter) {
    return Arrays.stream(clz.getFields())
        .filter(field -> variableNameFilter.test(field.getName()))
        .map(field -> packException(() -> field.get(null)))
        .filter(obj -> obj instanceof String)
        .map(obj -> (String) obj)
        .collect(Collectors.toCollection(LinkedList::new));
  }

  public static String toString(Throwable e) {
    var sw = new StringWriter();
    var pw = new PrintWriter(sw);
    e.printStackTrace(pw);
    return sw.toString();
  }

  public static Pattern wildcardToPattern(String string) {
    return Pattern.compile(
        string.replaceAll("\\?", ".").replaceAll("\\*", ".*"), Pattern.CASE_INSENSITIVE);
  }

  /**
   * @param supplier check logic
   * @param remainingMs to break loop
   * @param debounce to double-check the status. Some brokers may return out-of-date cluster state,
   *     so you can set a positive value to keep the loop until to debounce is completed
   */
  public static CompletionStage<Boolean> loop(
      Supplier<CompletionStage<Boolean>> supplier, long remainingMs, final int debounce) {
    return loop(supplier, remainingMs, debounce, debounce);
  }

  private static CompletionStage<Boolean> loop(
      Supplier<CompletionStage<Boolean>> supplier,
      long remainingMs,
      final int debounce,
      int remainingDebounce) {
    if (remainingMs <= 0) return CompletableFuture.completedFuture(false);
    var start = System.currentTimeMillis();
    return supplier
        .get()
        .thenCompose(
            match -> {
              // everything is good!!!
              if (match && remainingDebounce <= 0) return CompletableFuture.completedFuture(true);

              // take a break before retry/debounce
              Utils.sleep(Duration.ofMillis(300));

              var remaining = remainingMs - (System.currentTimeMillis() - start);

              // keep debounce
              if (match) return loop(supplier, remaining, debounce, remainingDebounce - 1);

              // reset debounce for retry
              return loop(supplier, remaining, debounce, debounce);
            });
  }

  public static <T> Collection<List<T>> chunk(Collection<T> input, int numberOfChunks) {
    var counter = new AtomicInteger(0);
    return input.stream()
        .collect(Collectors.groupingBy(s -> counter.getAndIncrement() % numberOfChunks))
        .values();
  }

  private Utils() {}
}
