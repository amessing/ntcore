/*----------------------------------------------------------------------------*/
/* Copyright (c) FIRST 2017. All Rights Reserved.                             */
/* Open Source Software - may be modified and shared by FRC teams. The code   */
/* must be accompanied by the FIRST BSD license file in the root directory of */
/* the project.                                                               */
/*----------------------------------------------------------------------------*/

package edu.wpi.first.networktables;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/**
 * A network table that knows its subtable path.
 */
public final class NetworkTable {
  /**
   * The path separator for sub-tables and keys
   *
   */
  public static final char PATH_SEPARATOR = '/';

  private final String path;
  private final String pathWithSep;
  private final NetworkTableInstance inst;

  public NetworkTable(NetworkTableInstance inst, String path) {
    this.path = path;
    this.pathWithSep = path + PATH_SEPARATOR;
    this.inst = inst;
  }

  /**
   * Gets the instance for the table.
   * @return Instance
   */
  public NetworkTableInstance getInstance() { return inst; }

  public String toString() { return "NetworkTable: " + path; }

  private final ConcurrentMap<String, NetworkTableEntry> entries = new ConcurrentHashMap<String, NetworkTableEntry>();

  /**
   * Gets the entry for a subkey.
   * @param key the key name
   * @return Network table entry.
   */
  public NetworkTableEntry getEntry(String key) {
    NetworkTableEntry entry = entries.get(key);
    if (entry == null) {
      entry = inst.getEntry(pathWithSep + key);
      entries.putIfAbsent(key, entry);
    }
    return entry;
  }

  /**
   * Listen to keys only within this table.
   * @param listener    listener to add
   * @param flags       {@link EntryListenerFlags} bitmask
   * @return Listener handle
   */
  public int addEntryListener(TableEntryListener listener, int flags) {
    final int prefixLen = path.length() + 1;
    return inst.addEntryListener(pathWithSep, (event) -> {
      String relativeKey = event.name.substring(prefixLen);
      if (relativeKey.indexOf(PATH_SEPARATOR) != -1)  // part of a subtable
        return;
      listener.valueChanged(this, relativeKey, event.getEntry(), event.value, event.flags);
    }, flags);
  }

  /**
   * Listen to a single key.
   * @param key         the key name
   * @param listener    listener to add
   * @param flags       {@link EntryListenerFlags} bitmask
   * @return Listener handle
   */
  public int addEntryListener(String key, TableEntryListener listener, int flags) {
    final NetworkTableEntry entry = getEntry(key);
    return inst.addEntryListener(entry, (event) -> {
      listener.valueChanged(this, key, entry, event.value, event.flags);
    }, flags);
  }

  /**
   * Remove an entry listener.
   * @param listener    listener handle
   */
  public void removeEntryListener(int listener) {
    inst.removeEntryListener(listener);
  }

  /**
   * Listen for sub-table creation.
   * This calls the listener once for each newly created sub-table.
   * It immediately calls the listener for any existing sub-tables.
   * @param listener        listener to add
   * @param localNotify     notify local changes as well as remote
   * @return Listener handle
   */
  public int addSubTableListener(TableListener listener, boolean localNotify) {
    int flags = EntryListenerFlags.kNew | EntryListenerFlags.kImmediate;
    if (localNotify)
      flags |= EntryListenerFlags.kLocal;

    final int prefixLen = path.length() + 1;
    final NetworkTable parent = this;

    return inst.addEntryListener(pathWithSep, new Consumer<EntryNotification>() {
      final Set<String> notifiedTables = new HashSet<String>();

      @Override
      public void accept(EntryNotification event) {
        String relativeKey = event.name.substring(prefixLen);
        int endSubTable = relativeKey.indexOf(PATH_SEPARATOR);
        if (endSubTable == -1)
          return;
        String subTableKey = relativeKey.substring(0, endSubTable);
        if (notifiedTables.contains(subTableKey))
          return;
        notifiedTables.add(subTableKey);
        listener.tableCreated(parent, subTableKey, parent.getSubTable(subTableKey));
      }
    }, flags);
  }

  /**
   * Remove a sub-table listener.
   * @param listener    listener handle
   */
  public void removeTableListener(int listener) {
    inst.removeEntryListener(listener);
  }

  /**
   * Returns the table at the specified key. If there is no table at the
   * specified key, it will create a new table
   *
   * @param key the name of the table relative to this one
   * @return a sub table relative to this one
   */
  public NetworkTable getSubTable(String key) {
    return new NetworkTable(inst, pathWithSep + key);
  }

  /**
   * Checks the table and tells if it contains the specified key
   *
   * @param key the key to search for
   * @return true if the table as a value assigned to the given key
   */
  public boolean containsKey(String key) {
    return getEntry(key).exists();
  }

  /**
   * @param key the key to search for
   * @return true if there is a subtable with the key which contains at least
   * one key/subtable of its own
   */
  public boolean containsSubTable(String key) {
    int[] handles = NetworkTablesJNI.getEntries(inst.getHandle(), pathWithSep + key + PATH_SEPARATOR, 0);
    return handles.length != 0;
  }

  /**
   * Gets all keys in the table (not including sub-tables).
   * @param types bitmask of types; 0 is treated as a "don't care".
   * @return keys currently in the table
   */
  public Set<String> getKeys(int types) {
    Set<String> keys = new HashSet<String>();
    int prefixLen = path.length() + 1;
    for (EntryInfo info : inst.getEntryInfo(pathWithSep, types)) {
      String relativeKey = info.name.substring(prefixLen);
      if (relativeKey.indexOf(PATH_SEPARATOR) != -1)
        continue;
      keys.add(relativeKey);
      // populate entries as we go
      if (entries.get(relativeKey) == null) {
        entries.putIfAbsent(relativeKey, new NetworkTableEntry(inst, info.entry));
      }
    }
    return keys;
  }

  /**
   * Gets all keys in the table (not including sub-tables).
   * @return keys currently in the table
   */
  public Set<String> getKeys() {
    return getKeys(0);
  }

  /**
   * Gets the names of all subtables in the table.
   * @return subtables currently in the table
   */
  public Set<String> getSubTables() {
    Set<String> keys = new HashSet<String>();
    int prefixLen = path.length() + 1;
    for (EntryInfo info : inst.getEntryInfo(pathWithSep, 0)) {
      String relativeKey = info.name.substring(prefixLen);
      int endSubTable = relativeKey.indexOf(PATH_SEPARATOR);
      if (endSubTable == -1)
        continue;
      keys.add(relativeKey.substring(0, endSubTable));
    }
    return keys;
  }

  /**
   * Deletes the specified key in this table. The key can
   * not be null.
   *
   * @param key the key name
   */
  public void delete(String key) {
    getEntry(key).delete();
  }

  /**
   * Put a value in the table
   *
   * @param key the key to be assigned to
   * @param value the value that will be assigned
   * @return False if the table key already exists with a different type
   */
  boolean putValue(String key, NetworkTableValue value) {
    return getEntry(key).setValue(value);
  }

  /**
   * Gets the current value in the table, setting it if it does not exist.
   * @param key the key
   * @param defaultValue the default value to set if key doesn't exist.
   * @returns False if the table key exists with a different type
   */
  boolean setDefaultValue(String key, NetworkTableValue defaultValue) {
    return getEntry(key).setDefaultValue(defaultValue);
  }

  /**
   * Gets the value associated with a key as an object
   *
   * @param key the key of the value to look up
   * @return the value associated with the given key, or nullptr if the key
   * does not exist
   */
  NetworkTableValue getValue(String key) {
    return getEntry(key).getValue();
  }

  /**
   * {@inheritDoc}
   */
  public String getPath() {
    return path;
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    }
    if (!(o instanceof NetworkTable)) {
      return false;
    }
    NetworkTable other = (NetworkTable) o;
    return inst.equals(other.inst) && path.equals(other.path);
  }

  @Override
  public int hashCode() {
    return Objects.hash(inst, path);
  }
}
