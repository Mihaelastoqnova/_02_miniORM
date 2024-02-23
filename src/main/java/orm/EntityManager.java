package orm;

import orm.annotation.Column;
import orm.annotation.Entity;
import orm.annotation.Id;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class EntityManager<E> implements DatabaseContext<E> {
    private static final String INSERT_TEMPLATE = "INSERT INTO %s (%s) VALUES (%s)";
    private static final String UPDATE_WITH_WHERE_TEMPLATE = "UPDATE %s SET %s WHERE %s";
    private static final String SELECT_WITH_WHERE_PLACEHOLDER_TEMPLATE = "SELECT %s FROM %s %s";
    private static final String CREATE_TABLE_TEMPLATE = "CREATE TABLE %s(%s)";
    private static final String ALTER_TABLE_TEMPLATE = "ALTER TABLE %s ADD COLUMN %s";
    private static final String EXISTING_COLUMNS_SQL = "SELECT COLUMN_NAME FROM information.schema.COLUMNS WHERE TABLE_SCHEMA = 'mini_orm' AND TABLE_NAME = ?";
    private Connection connection;

    public EntityManager(Connection connection) {
        this.connection = connection;
    }

    @Override
    public void doCreate(Class<E> entityClass) throws SQLException {
        String tableName = getTableName(entityClass);
        String sql = String.format(CREATE_TABLE_TEMPLATE, tableName, getAllFieldAndDataTypes(entityClass));
        this.connection.prepareStatement(sql).execute();
    }

    @Override
    public void doAlter(E entity) throws SQLException {
        String newColumns = getColumnsNotExistingInTable(entity);
        String sql = String.format(ALTER_TABLE_TEMPLATE, getTableName(entity), newColumns);
        this.connection.prepareStatement(sql).execute();
    }

    @Override
    public boolean delete(E entity) throws SQLException, IllegalAccessException {
        PreparedStatement preparedStatement = this.connection.prepareStatement(String.format("DELETE FROM %s WHERE id = ?", getTableName(entity)));
        Field fieldId = Arrays.stream(entity.getClass().getDeclaredFields()).filter(f -> f.getName().equals("id")).findFirst().get();
        fieldId.setAccessible(true);
        long id = fieldId.getLong(entity);
        preparedStatement.setLong(1, id);
        int deletedRows = preparedStatement.executeUpdate();
        if (deletedRows == 0) {
            System.out.println("No rows deleted.");
            return false;
        } else {
            System.out.println(deletedRows + " rows deleted from table.");
            return true;
        }
    }

    private String getColumnsNotExistingInTable(E entity) throws SQLException {
        List<String> existingColumns = getExistingColumns(entity);

        return Arrays.stream(entity.getClass().getDeclaredFields())
                .filter(f -> !existingColumns.contains(f.getAnnotation(Column.class).name()))
                .map(f -> String.format("%s %s", getFieldName(f), getFieldType(f)))
                .collect(Collectors.joining(", "));
    }

    private List<String> getExistingColumns(E entity) throws SQLException {
        List<String> existingColumns = new ArrayList<>();
        PreparedStatement preparedStatement = connection.prepareStatement(EXISTING_COLUMNS_SQL);
        preparedStatement.setString(1, getTableName(entity));
        ResultSet resultSet = preparedStatement.executeQuery();

        while (resultSet.next()) {
            existingColumns.add(resultSet.getString(1));
        }
        return existingColumns;
    }

    private Object getAllFieldAndDataTypes(Class<E> entityClass) {
        List<String> dbColumns = new ArrayList<>();
        Field[] declaredFields = entityClass.getDeclaredFields();
        for (Field declaredField : declaredFields) {
            StringBuilder sb = new StringBuilder(String.format("%s %s", getFieldName(declaredField), getFieldType(declaredField)));
            if (declaredField.isAnnotationPresent(Id.class)) {
                sb.append("PRIMARY KEY AUTO_INCREMENT, ");
            }
            dbColumns.add(sb.toString());
        }
        return String.join(", ", dbColumns);
    }

    private String getFieldType(Field declaredField) {
        return switch (declaredField.getType().getSimpleName()) {
            case "int", "Integer" -> "INT";
            case "long", "Long" -> "BIGINT";
            case "String" -> "VARCHAR(255)";
            case "double", "Double" -> "DOUBLE";
            case "LocalDate" -> "DATE";
            default -> "";
        };
    }

    private String getFieldName(Field declaredField) {
        return declaredField.getAnnotation(Column.class).name();
    }

    @Override
    public boolean persist(E entity) throws SQLException, IllegalAccessException {
        Field idColumn = getIdColumn(entity);
        idColumn.setAccessible(true);
        Object idValue = idColumn.get(entity);

        if (idValue == null || (long) idValue == 0) {
            return doInsert(entity);
        }

        return doUpdate(entity, idColumn, idValue);
    }

    @Override
    public Iterable<E> find(Class<E> table) throws SQLException, InvocationTargetException, NoSuchMethodException, InstantiationException, IllegalAccessException {
        return find(table, null);
    }

    @Override
    public Iterable<E> find(Class<E> table, String where) throws SQLException, InvocationTargetException, NoSuchMethodException, InstantiationException, IllegalAccessException {
        return baseFind(table, where, null);
    }

    private E generateEntity(Class<E> table, ResultSet resultSet) throws NoSuchMethodException, InvocationTargetException, InstantiationException, IllegalAccessException, SQLException {
        E result = table.getDeclaredConstructor().newInstance();
        Field[] declaredFields = table.getDeclaredFields();

        for (Field declaredField : declaredFields) {
            fillField(result, declaredField, resultSet);
        }
        return result;
    }

    private void fillField(E result, Field declaredField, ResultSet resultSet) throws SQLException, IllegalAccessException {
        String dbFieldName = declaredField.getAnnotation(Column.class).name();
        Class<?> javaType = declaredField.getType();
        declaredField.setAccessible(true);

        if (javaType == int.class || javaType == Integer.class) {
            int value = resultSet.getInt(dbFieldName);
            declaredField.setInt(result, value);
        } else if (javaType == long.class || javaType == Long.class) {
            long value = resultSet.getLong(dbFieldName);
            declaredField.setLong(result, value);
        } else if (javaType == LocalDate.class) {
            LocalDate value = resultSet.getObject(dbFieldName, LocalDate.class);
            declaredField.set(result, value);
        } else if (javaType == String.class) {
            String value = resultSet.getString(dbFieldName);
            declaredField.set(result, value);
        } else {
            throw new RuntimeException("Unsupported type" + javaType);
        }
    }

    @Override
    public E findFirst(Class<E> table) throws SQLException, InvocationTargetException, NoSuchMethodException, InstantiationException, IllegalAccessException {
        return findFirst(table, null);
    }

    @Override
    public E findFirst(Class<E> table, String where) throws SQLException, InvocationTargetException, NoSuchMethodException, InstantiationException, IllegalAccessException {
        List<E> result = baseFind(table, where, 1);

        if (result.isEmpty()) {
            return null;
        }
        return result.get(0);
    }

    private List<E> baseFind(Class<E> table, String where, Integer limit) throws SQLException, NoSuchMethodException, InvocationTargetException, InstantiationException, IllegalAccessException {
        String fieldList = "*";
        String tableName = getTableName(table);
        String whereClause = where == null ? "" : "WHERE" + where;
        String limitClause = limit == null ? "" : " LIMIT" + limit;

        String selectStatement = String.format(SELECT_WITH_WHERE_PLACEHOLDER_TEMPLATE,
                fieldList,
                tableName,
                whereClause + limitClause);

        PreparedStatement preparedStatement = connection.prepareStatement(selectStatement);
        ResultSet resultSet = preparedStatement.executeQuery();

        List<E> result = new ArrayList<>();
        while (resultSet.next()) {
            E current = generateEntity(table, resultSet);
            result.add(current);
        }
        return result;
    }

    private boolean doUpdate(E entity, Field idColumn, Object idValue) throws IllegalAccessException, SQLException {
        String tableName = getTableName(entity);
        List<String> columns = getColumnsWithoutId(entity);
        List<String> values = getColumnsValuesWithoutId(entity);

        List<String> columnsWithValues = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            String s = columns.get(i) + "=" + values.get(i);
            columnsWithValues.add(s);
        }

        String idCondition = String.format("%s=%s", idColumn.getName(), idValue.toString());

        String updateQuery = String.format(UPDATE_WITH_WHERE_TEMPLATE, tableName,
                String.join(",", columnsWithValues), idCondition);
        PreparedStatement statement = connection.prepareStatement(updateQuery);
        int updateCount = statement.executeUpdate();
        return updateCount == 1;
    }

    private Field getIdColumn(E entity) {
        Field[] declaredFields = entity.getClass().getDeclaredFields();

        for (Field declaredField : declaredFields) {
            if (declaredField.isAnnotationPresent(Id.class)) {
                return declaredField;
            }
        }
        throw new RuntimeException("Entity has no Id column");
    }

    private boolean doInsert(E entity) throws IllegalAccessException, SQLException {
        String tableName = getTableName(entity);
        List<String> columnsList = getColumnsWithoutId(entity);
        List<String> values = getColumnsValuesWithoutId(entity);

        String formattedInsert = String.format(INSERT_TEMPLATE, tableName, String.join(",", columnsList),
                String.join(",", values));

        PreparedStatement statement = connection.prepareStatement(formattedInsert);
        int changedRows = statement.executeUpdate();

        return changedRows == 1;
    }


    private List<String> getColumnsValuesWithoutId(E entity) throws IllegalAccessException {
        List<String> result = new ArrayList<>();
        Field[] declaredFields = entity.getClass().getDeclaredFields();

        for (Field declaredField : declaredFields) {
            if (declaredField.isAnnotationPresent(Id.class)) {
                continue;
            }
            if (!declaredField.isAnnotationPresent(Column.class)) {
                continue;
            }
            declaredField.setAccessible(true);
            Object fieldValue = declaredField.get(entity);

            result.add("'" + fieldValue.toString() + "'");
        }
        return result;
    }

    private List<String> getColumnsWithoutId(E entity) {
        List<String> result = new ArrayList<>();
        Field[] declaredFields = entity.getClass().getDeclaredFields();

        for (Field declaredField : declaredFields) {
            if (declaredField.isAnnotationPresent(Id.class)) {
                continue;
            }

            Column column = declaredField.getAnnotation(Column.class);
            if (column == null) {
                continue;
            }
            result.add(column.name());
        }
        return result;
    }

    private String getTableName(E entity) {
        Entity annotation = entity.getClass().getAnnotation(Entity.class);
        if (annotation == null) {
            throw new RuntimeException("No Entity annotation present");
        }
        return annotation.name();
    }

    // Fix
    private String getTableName(Class<E> clazz) {
        Entity annotation = clazz.getClass().getAnnotation(Entity.class);
        if (annotation == null) {
            throw new RuntimeException("No Entity annotation present");
        }
        return annotation.name();
    }
}
