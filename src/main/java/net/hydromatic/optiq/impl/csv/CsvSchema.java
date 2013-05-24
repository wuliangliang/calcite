/*
// Licensed to Julian Hyde under one or more contributor license
// agreements. See the NOTICE file distributed with this work for
// additional information regarding copyright ownership.
//
// Julian Hyde licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except in
// compliance with the License. You may obtain a copy of the License at:
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
*/
package net.hydromatic.optiq.impl.csv;

import au.com.bytecode.opencsv.CSVReader;
import net.hydromatic.linq4j.*;
import net.hydromatic.linq4j.expressions.Expression;

import net.hydromatic.optiq.*;
import net.hydromatic.optiq.impl.TableInSchemaImpl;

import net.hydromatic.optiq.impl.java.JavaTypeFactory;
import net.hydromatic.optiq.jdbc.OptiqConnection;
import org.eigenbase.reltype.RelDataType;
import org.eigenbase.util.Pair;

import java.io.*;
import java.util.*;

/**
 * Schema mapped onto a directory of CSV files. Each table in the schema
 * is a CSV file in that directory.
 */
public class CsvSchema implements Schema {
  private final Schema parentSchema;
  private final File directoryFile;
  private final Expression expression;
  private final JavaTypeFactory typeFactory;
  private final RelDataType stringType;
  private Map<String, TableInSchema> map;

  public CsvSchema(
      Schema parentSchema,
      File directoryFile,
      Expression expression)
  {
    this.parentSchema = parentSchema;
    this.directoryFile = directoryFile;
    this.expression = expression;
    this.typeFactory = ((OptiqConnection) getQueryProvider()).getTypeFactory();
    this.stringType = typeFactory.createJavaType(String.class);
  }

  public Expression getExpression() {
    return expression;
  }

  public List<TableFunction> getTableFunctions(String name) {
    return Collections.emptyList();
  }

  public QueryProvider getQueryProvider() {
    return parentSchema.getQueryProvider();
  }

  public Collection<TableInSchema> getTables() {
    return computeMap().values();
  }

  public <T> CsvTable<T> getTable(String name, Class<T> elementType) {
    final TableInSchema tableInSchema = computeMap().get(name);
    return tableInSchema == null
        ? null
        : (CsvTable<T>) tableInSchema.getTable(elementType);
  }

  public Map<String, List<TableFunction>> getTableFunctions() {
    // this kind of schema does not have table functions
    return Collections.emptyMap();
  }

  public Schema getSubSchema(String name) {
    // this kind of schema does not have sub-schemas
    return null;
  }

  public Collection<String> getSubSchemaNames() {
    // this kind of schema does not have sub-schemas
    return Collections.emptyList();
  }

  /** Returns the map of tables by name, populating the map on first use. */
  private synchronized Map<String, TableInSchema> computeMap() {
    if (map == null) {
      map = new HashMap<String, TableInSchema>();
      File[] files = directoryFile.listFiles(
          new FilenameFilter() {
            public boolean accept(File dir, String name) {
              return name.endsWith(".csv");
            }
          });
      for (File file : files) {
        String tableName = file.getName();
        if (tableName.endsWith(".csv")) {
          tableName = tableName.substring(
              0, tableName.length() - ".csv".length());
        }
        final RelDataType rowType = deduceRowType(file);
        final CsvTable table =
            new CsvTable(String[].class, rowType, this, tableName);
        map.put(
            tableName,
            new TableInSchemaImpl(this, tableName, TableType.TABLE, table));
      }
    }
    return map;
  }

  private RelDataType deduceRowType(File file) {
    final List<RelDataType> types = new ArrayList<RelDataType>();
    final List<String> names = new ArrayList<String>();
    CSVReader reader = null;
    try {
      reader = new CSVReader(new FileReader(file));
      final String[] strings = reader.readNext();
      for (String string : strings) {
        RelDataType type;
        String name;
        final int colon = string.indexOf(':');
        if (colon >= 0) {
          name = string.substring(0, colon);
          String typeString = string.substring(colon + 1);
          if (typeString.equals("String")) {
            type = stringType;
          } else {
            try {
              type = typeFactory.createJavaType(Class.forName(typeString));
            } catch (ClassNotFoundException e) {
              type = stringType;
            }
          }
        } else {
          name = string;
          type = stringType;
        }
        names.add(name);
        types.add(type);
      }
    } catch (IOException e) {
      // ignore
    } finally {
      if (reader != null) {
        try {
          reader.close();
        } catch (IOException e) {
          // ignore
        }
      }
    }
    if (names.isEmpty()) {
      names.add("line");
      types.add(stringType);
    }
    return typeFactory.createStructType(Pair.zip(names, types));
  }
}

// End CsvSchema.java
