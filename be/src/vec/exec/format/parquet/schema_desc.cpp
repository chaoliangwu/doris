// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

#include "schema_desc.h"

#include <ctype.h>

#include <algorithm>
#include <ostream>
#include <utility>

#include "common/cast_set.h"
#include "common/logging.h"
#include "runtime/define_primitive_type.h"
#include "util/slice.h"
#include "util/string_util.h"
#include "vec/data_types/data_type_array.h"
#include "vec/data_types/data_type_factory.hpp"
#include "vec/data_types/data_type_map.h"
#include "vec/data_types/data_type_struct.h"
#include "vec/exec/format/table/table_format_reader.h"

namespace doris::vectorized {
#include "common/compile_check_begin.h"

static bool is_group_node(const tparquet::SchemaElement& schema) {
    return schema.num_children > 0;
}

static bool is_list_node(const tparquet::SchemaElement& schema) {
    return schema.__isset.converted_type && schema.converted_type == tparquet::ConvertedType::LIST;
}

static bool is_map_node(const tparquet::SchemaElement& schema) {
    return schema.__isset.converted_type &&
           (schema.converted_type == tparquet::ConvertedType::MAP ||
            schema.converted_type == tparquet::ConvertedType::MAP_KEY_VALUE);
}

static bool is_repeated_node(const tparquet::SchemaElement& schema) {
    return schema.__isset.repetition_type &&
           schema.repetition_type == tparquet::FieldRepetitionType::REPEATED;
}

static bool is_required_node(const tparquet::SchemaElement& schema) {
    return schema.__isset.repetition_type &&
           schema.repetition_type == tparquet::FieldRepetitionType::REQUIRED;
}

static bool is_optional_node(const tparquet::SchemaElement& schema) {
    return schema.__isset.repetition_type &&
           schema.repetition_type == tparquet::FieldRepetitionType::OPTIONAL;
}

static int num_children_node(const tparquet::SchemaElement& schema) {
    return schema.__isset.num_children ? schema.num_children : 0;
}

/**
 * `repeated_parent_def_level` is the definition level of the first ancestor node whose repetition_type equals REPEATED.
 * Empty array/map values are not stored in doris columns, so have to use `repeated_parent_def_level` to skip the
 * empty or null values in ancestor node.
 *
 * For instance, considering an array of strings with 3 rows like the following:
 * null, [], [a, b, c]
 * We can store four elements in data column: null, a, b, c
 * and the offsets column is: 1, 1, 4
 * and the null map is: 1, 0, 0
 * For the i-th row in array column: range from `offsets[i - 1]` until `offsets[i]` represents the elements in this row,
 * so we can't store empty array/map values in doris data column.
 * As a comparison, spark does not require `repeated_parent_def_level`,
 * because the spark column stores empty array/map values , and use anther length column to indicate empty values.
 * Please reference: https://github.com/apache/spark/blob/master/sql/core/src/main/java/org/apache/spark/sql/execution/datasources/parquet/ParquetColumnVector.java
 *
 * Furthermore, we can also avoid store null array/map values in doris data column.
 * The same three rows as above, We can only store three elements in data column: a, b, c
 * and the offsets column is: 0, 0, 3
 * and the null map is: 1, 0, 0
 *
 * Inherit the repetition and definition level from parent node, if the parent node is repeated,
 * we should set repeated_parent_def_level = definition_level, otherwise as repeated_parent_def_level.
 * @param parent parent node
 * @param repeated_parent_def_level the first ancestor node whose repetition_type equals REPEATED
 */
static void set_child_node_level(FieldSchema* parent, int16_t repeated_parent_def_level) {
    for (auto& child : parent->children) {
        child.repetition_level = parent->repetition_level;
        child.definition_level = parent->definition_level;
        child.repeated_parent_def_level = repeated_parent_def_level;
    }
}

static bool is_struct_list_node(const tparquet::SchemaElement& schema) {
    const std::string& name = schema.name;
    static const Slice array_slice("array", 5);
    static const Slice tuple_slice("_tuple", 6);
    Slice slice(name);
    return slice == array_slice || slice.ends_with(tuple_slice);
}

std::string FieldSchema::debug_string() const {
    std::stringstream ss;
    ss << "FieldSchema(name=" << name << ", R=" << repetition_level << ", D=" << definition_level;
    if (children.size() > 0) {
        ss << ", type=" << data_type->get_name() << ", children=[";
        for (int i = 0; i < children.size(); ++i) {
            if (i != 0) {
                ss << ", ";
            }
            ss << children[i].debug_string();
        }
        ss << "]";
    } else {
        ss << ", physical_type=" << physical_type;
    }
    ss << ")";
    return ss.str();
}

Status FieldDescriptor::parse_from_thrift(const std::vector<tparquet::SchemaElement>& t_schemas) {
    if (t_schemas.size() == 0 || !is_group_node(t_schemas[0])) {
        return Status::InvalidArgument("Wrong parquet root schema element");
    }
    auto& root_schema = t_schemas[0];
    _fields.resize(root_schema.num_children);
    _next_schema_pos = 1;

    for (int i = 0; i < root_schema.num_children; ++i) {
        RETURN_IF_ERROR(parse_node_field(t_schemas, _next_schema_pos, &_fields[i]));
        if (_name_to_field.find(_fields[i].name) != _name_to_field.end()) {
            return Status::InvalidArgument("Duplicated field name: {}", _fields[i].name);
        }
        _name_to_field.emplace(_fields[i].name, &_fields[i]);
    }

    if (_next_schema_pos != t_schemas.size()) {
        return Status::InvalidArgument("Remaining {} unparsed schema elements",
                                       t_schemas.size() - _next_schema_pos);
    }

    return Status::OK();
}

Status FieldDescriptor::parse_node_field(const std::vector<tparquet::SchemaElement>& t_schemas,
                                         size_t curr_pos, FieldSchema* node_field) {
    if (curr_pos >= t_schemas.size()) {
        return Status::InvalidArgument("Out-of-bounds index of schema elements");
    }
    auto& t_schema = t_schemas[curr_pos];
    if (is_group_node(t_schema)) {
        // nested structure or nullable list
        return parse_group_field(t_schemas, curr_pos, node_field);
    }
    if (is_repeated_node(t_schema)) {
        // repeated <primitive-type> <name> (LIST)
        // produce required list<element>
        node_field->repetition_level++;
        node_field->definition_level++;
        node_field->children.resize(1);
        set_child_node_level(node_field, node_field->definition_level);
        auto child = &node_field->children[0];
        parse_physical_field(t_schema, false, child);

        node_field->name = t_schema.name;
        node_field->data_type = std::make_shared<DataTypeArray>(make_nullable(child->data_type));
        _next_schema_pos = curr_pos + 1;
        node_field->field_id = t_schema.__isset.field_id ? t_schema.field_id : -1;
    } else {
        bool is_optional = is_optional_node(t_schema);
        if (is_optional) {
            node_field->definition_level++;
        }
        parse_physical_field(t_schema, is_optional, node_field);
        _next_schema_pos = curr_pos + 1;
    }
    return Status::OK();
}

void FieldDescriptor::parse_physical_field(const tparquet::SchemaElement& physical_schema,
                                           bool is_nullable, FieldSchema* physical_field) {
    physical_field->name = physical_schema.name;
    physical_field->parquet_schema = physical_schema;
    physical_field->physical_type = physical_schema.type;
    _physical_fields.push_back(physical_field);
    physical_field->physical_column_index = cast_set<int>(_physical_fields.size() - 1);
    auto type = get_doris_type(physical_schema, is_nullable);
    physical_field->data_type = type.first;
    physical_field->is_type_compatibility = type.second;
    physical_field->field_id = physical_schema.__isset.field_id ? physical_schema.field_id : -1;
}

std::pair<DataTypePtr, bool> FieldDescriptor::get_doris_type(
        const tparquet::SchemaElement& physical_schema, bool nullable) {
    std::pair<DataTypePtr, bool> ans = {std::make_shared<DataTypeNothing>(), false};
    try {
        if (physical_schema.__isset.logicalType) {
            ans = convert_to_doris_type(physical_schema.logicalType, nullable);
        } else if (physical_schema.__isset.converted_type) {
            ans = convert_to_doris_type(physical_schema, nullable);
        }
    } catch (...) {
        // ignore
    }
    if (ans.first->get_primitive_type() == PrimitiveType::INVALID_TYPE) {
        switch (physical_schema.type) {
        case tparquet::Type::BOOLEAN:
            ans.first = DataTypeFactory::instance().create_data_type(TYPE_BOOLEAN, nullable);
            break;
        case tparquet::Type::INT32:
            ans.first = DataTypeFactory::instance().create_data_type(TYPE_INT, nullable);
            break;
        case tparquet::Type::INT64:
            ans.first = DataTypeFactory::instance().create_data_type(TYPE_BIGINT, nullable);
            break;
        case tparquet::Type::INT96:
            // in most cases, it's a nano timestamp
            ans.first =
                    DataTypeFactory::instance().create_data_type(TYPE_DATETIMEV2, nullable, 0, 6);
            break;
        case tparquet::Type::FLOAT:
            ans.first = DataTypeFactory::instance().create_data_type(TYPE_FLOAT, nullable);
            break;
        case tparquet::Type::DOUBLE:
            ans.first = DataTypeFactory::instance().create_data_type(TYPE_DOUBLE, nullable);
            break;
        case tparquet::Type::BYTE_ARRAY:
            [[fallthrough]];
        case tparquet::Type::FIXED_LEN_BYTE_ARRAY:
            ans.first = DataTypeFactory::instance().create_data_type(TYPE_STRING, nullable);
            break;
        default:
            throw Exception(Status::InternalError("Not supported parquet logicalType{}",
                                                  physical_schema.type));
            break;
        }
    }
    return ans;
}

// Copy from org.apache.iceberg.avro.AvroSchemaUtil#validAvroName
static bool is_valid_avro_name(const std::string& name) {
    size_t length = name.length();
    char first = name[0];
    if (!isalpha(first) && first != '_') {
        return false;
    }

    for (size_t i = 1; i < length; i++) {
        char character = name[i];
        if (!isalpha(character) && !isdigit(character) && character != '_') {
            return false;
        }
    }
    return true;
}

// Copy from org.apache.iceberg.avro.AvroSchemaUtil#sanitize
static void sanitize_avro_name(std::ostringstream& buf, char character) {
    if (isdigit(character)) {
        buf << '_' << character;
    } else {
        std::stringstream ss;
        ss << std::hex << (int)character;
        std::string hex_str = ss.str();
        buf << "_x" << doris::to_lower(hex_str);
    }
}

// Copy from org.apache.iceberg.avro.AvroSchemaUtil#sanitize
static std::string sanitize_avro_name(const std::string& name) {
    std::ostringstream buf;
    size_t length = name.length();
    char first = name[0];
    if (!isalpha(first) && first != '_') {
        sanitize_avro_name(buf, first);
    } else {
        buf << first;
    }

    for (size_t i = 1; i < length; i++) {
        char character = name[i];
        if (!isalpha(character) && !isdigit(character) && character != '_') {
            sanitize_avro_name(buf, character);
        } else {
            buf << character;
        }
    }
    return buf.str();
}

void FieldDescriptor::iceberg_sanitize(const std::vector<std::string>& read_columns) {
    for (const std::string& col : read_columns) {
        if (!is_valid_avro_name(col)) {
            std::string sanitize_name = sanitize_avro_name(col);
            auto it = _name_to_field.find(sanitize_name);
            if (it != _name_to_field.end()) {
                FieldSchema* schema = const_cast<FieldSchema*>(it->second);
                schema->name = col;
                _name_to_field.emplace(col, schema);
                _name_to_field.erase(sanitize_name);
            }
        }
    }
}

std::pair<DataTypePtr, bool> FieldDescriptor::convert_to_doris_type(
        tparquet::LogicalType logicalType, bool nullable) {
    std::pair<DataTypePtr, bool> ans = {std::make_shared<DataTypeNothing>(), false};
    bool& is_type_compatibility = ans.second;
    if (logicalType.__isset.STRING) {
        ans.first = DataTypeFactory::instance().create_data_type(TYPE_STRING, nullable);
    } else if (logicalType.__isset.DECIMAL) {
        ans.first = DataTypeFactory::instance().create_data_type(TYPE_DECIMAL128I, nullable,
                                                                 logicalType.DECIMAL.precision,
                                                                 logicalType.DECIMAL.scale);
    } else if (logicalType.__isset.DATE) {
        ans.first = DataTypeFactory::instance().create_data_type(TYPE_DATEV2, nullable);
    } else if (logicalType.__isset.INTEGER) {
        if (logicalType.INTEGER.isSigned) {
            if (logicalType.INTEGER.bitWidth <= 8) {
                ans.first = DataTypeFactory::instance().create_data_type(TYPE_TINYINT, nullable);
            } else if (logicalType.INTEGER.bitWidth <= 16) {
                ans.first = DataTypeFactory::instance().create_data_type(TYPE_SMALLINT, nullable);
            } else if (logicalType.INTEGER.bitWidth <= 32) {
                ans.first = DataTypeFactory::instance().create_data_type(TYPE_INT, nullable);
            } else {
                ans.first = DataTypeFactory::instance().create_data_type(TYPE_BIGINT, nullable);
            }
        } else {
            is_type_compatibility = true;
            if (logicalType.INTEGER.bitWidth <= 8) {
                ans.first = DataTypeFactory::instance().create_data_type(TYPE_SMALLINT, nullable);
            } else if (logicalType.INTEGER.bitWidth <= 16) {
                ans.first = DataTypeFactory::instance().create_data_type(TYPE_INT, nullable);
            } else if (logicalType.INTEGER.bitWidth <= 32) {
                ans.first = DataTypeFactory::instance().create_data_type(TYPE_BIGINT, nullable);
            } else {
                ans.first = DataTypeFactory::instance().create_data_type(TYPE_LARGEINT, nullable);
            }
        }
    } else if (logicalType.__isset.TIME) {
        ans.first = DataTypeFactory::instance().create_data_type(TYPE_TIMEV2, nullable);
    } else if (logicalType.__isset.TIMESTAMP) {
        ans.first = DataTypeFactory::instance().create_data_type(
                TYPE_DATETIMEV2, nullable, 0, logicalType.TIMESTAMP.unit.__isset.MILLIS ? 3 : 6);
    } else {
        throw Exception(Status::InternalError("Not supported parquet logicalType"));
    }
    return ans;
}

std::pair<DataTypePtr, bool> FieldDescriptor::convert_to_doris_type(
        const tparquet::SchemaElement& physical_schema, bool nullable) {
    std::pair<DataTypePtr, bool> ans = {std::make_shared<DataTypeNothing>(), false};
    bool& is_type_compatibility = ans.second;
    switch (physical_schema.converted_type) {
    case tparquet::ConvertedType::type::UTF8:
        ans.first = DataTypeFactory::instance().create_data_type(TYPE_STRING, nullable);
        break;
    case tparquet::ConvertedType::type::DECIMAL:
        ans.first = DataTypeFactory::instance().create_data_type(
                TYPE_DECIMAL128I, nullable, physical_schema.precision, physical_schema.scale);
        break;
    case tparquet::ConvertedType::type::DATE:
        ans.first = DataTypeFactory::instance().create_data_type(TYPE_DATEV2, nullable);
        break;
    case tparquet::ConvertedType::type::TIME_MILLIS:
        [[fallthrough]];
    case tparquet::ConvertedType::type::TIME_MICROS:
        ans.first = DataTypeFactory::instance().create_data_type(TYPE_TIMEV2, nullable);
        break;
    case tparquet::ConvertedType::type::TIMESTAMP_MILLIS:
        ans.first = DataTypeFactory::instance().create_data_type(TYPE_DATETIMEV2, nullable, 0, 3);
        break;
    case tparquet::ConvertedType::type::TIMESTAMP_MICROS:
        ans.first = DataTypeFactory::instance().create_data_type(TYPE_DATETIMEV2, nullable, 0, 6);
        break;
    case tparquet::ConvertedType::type::INT_8:
        ans.first = DataTypeFactory::instance().create_data_type(TYPE_TINYINT, nullable);
        break;
    case tparquet::ConvertedType::type::UINT_8:
        is_type_compatibility = true;
        [[fallthrough]];
    case tparquet::ConvertedType::type::INT_16:
        ans.first = DataTypeFactory::instance().create_data_type(TYPE_SMALLINT, nullable);
        break;
    case tparquet::ConvertedType::type::UINT_16:
        is_type_compatibility = true;
        [[fallthrough]];
    case tparquet::ConvertedType::type::INT_32:
        ans.first = DataTypeFactory::instance().create_data_type(TYPE_INT, nullable);
        break;
    case tparquet::ConvertedType::type::UINT_32:
        is_type_compatibility = true;
        [[fallthrough]];
    case tparquet::ConvertedType::type::INT_64:
        ans.first = DataTypeFactory::instance().create_data_type(TYPE_BIGINT, nullable);
        break;
    case tparquet::ConvertedType::type::UINT_64:
        is_type_compatibility = true;
        ans.first = DataTypeFactory::instance().create_data_type(TYPE_LARGEINT, nullable);
        break;
    default:
        throw Exception(Status::InternalError("Not supported parquet ConvertedType: {}",
                                              physical_schema.converted_type));
    }
    return ans;
}

Status FieldDescriptor::parse_group_field(const std::vector<tparquet::SchemaElement>& t_schemas,
                                          size_t curr_pos, FieldSchema* group_field) {
    auto& group_schema = t_schemas[curr_pos];
    if (is_map_node(group_schema)) {
        // the map definition:
        // optional group <name> (MAP) {
        //   repeated group map (MAP_KEY_VALUE) {
        //     required <type> key;
        //     optional <type> value;
        //   }
        // }
        return parse_map_field(t_schemas, curr_pos, group_field);
    }
    if (is_list_node(group_schema)) {
        // the list definition:
        // optional group <name> (LIST) {
        //   repeated group [bag | list] { // hive or spark
        //     optional <type> [array_element | element]; // hive or spark
        //   }
        // }
        return parse_list_field(t_schemas, curr_pos, group_field);
    }

    if (is_repeated_node(group_schema)) {
        group_field->repetition_level++;
        group_field->definition_level++;
        group_field->children.resize(1);
        set_child_node_level(group_field, group_field->definition_level);
        auto struct_field = &group_field->children[0];
        // the list of struct:
        // repeated group <name> (LIST) {
        //   optional/required <type> <name>;
        //   ...
        // }
        // produce a non-null list<struct>
        RETURN_IF_ERROR(parse_struct_field(t_schemas, curr_pos, struct_field));

        group_field->name = group_schema.name;
        group_field->data_type =
                std::make_shared<DataTypeArray>(make_nullable(struct_field->data_type));
        group_field->field_id = group_schema.__isset.field_id ? group_schema.field_id : -1;
    } else {
        RETURN_IF_ERROR(parse_struct_field(t_schemas, curr_pos, group_field));
    }

    return Status::OK();
}

Status FieldDescriptor::parse_list_field(const std::vector<tparquet::SchemaElement>& t_schemas,
                                         size_t curr_pos, FieldSchema* list_field) {
    // the list definition:
    // spark and hive have three level schemas but with different schema name
    // spark: <column-name> - "list" - "element"
    // hive: <column-name> - "bag" - "array_element"
    // parse three level schemas to two level primitive like: LIST<INT>,
    // or nested structure like: LIST<MAP<INT, INT>>
    auto& first_level = t_schemas[curr_pos];
    if (first_level.num_children != 1) {
        return Status::InvalidArgument("List element should have only one child");
    }

    if (curr_pos + 1 >= t_schemas.size()) {
        return Status::InvalidArgument("List element should have the second level schema");
    }

    if (first_level.repetition_type == tparquet::FieldRepetitionType::REPEATED) {
        return Status::InvalidArgument("List element can't be a repeated schema");
    }

    // the repeated schema element
    auto& second_level = t_schemas[curr_pos + 1];
    if (second_level.repetition_type != tparquet::FieldRepetitionType::REPEATED) {
        return Status::InvalidArgument("The second level of list element should be repeated");
    }

    // This indicates if this list is nullable.
    bool is_optional = is_optional_node(first_level);
    if (is_optional) {
        list_field->definition_level++;
    }
    list_field->repetition_level++;
    list_field->definition_level++;
    list_field->children.resize(1);
    FieldSchema* list_child = &list_field->children[0];

    size_t num_children = num_children_node(second_level);
    if (num_children > 0) {
        if (num_children == 1 && !is_struct_list_node(second_level)) {
            // optional field, and the third level element is the nested structure in list
            // produce nested structure like: LIST<INT>, LIST<MAP>, LIST<LIST<...>>
            // skip bag/list, it's a repeated element.
            set_child_node_level(list_field, list_field->definition_level);
            RETURN_IF_ERROR(parse_node_field(t_schemas, curr_pos + 2, list_child));
        } else {
            // required field, produce the list of struct
            set_child_node_level(list_field, list_field->definition_level);
            RETURN_IF_ERROR(parse_struct_field(t_schemas, curr_pos + 1, list_child));
        }
    } else if (num_children == 0) {
        // required two level list, for compatibility reason.
        set_child_node_level(list_field, list_field->definition_level);
        parse_physical_field(second_level, false, list_child);
        _next_schema_pos = curr_pos + 2;
    }

    list_field->name = first_level.name;
    list_field->data_type =
            std::make_shared<DataTypeArray>(make_nullable(list_field->children[0].data_type));
    if (is_optional) {
        list_field->data_type = make_nullable(list_field->data_type);
    }
    list_field->field_id = first_level.__isset.field_id ? first_level.field_id : -1;

    return Status::OK();
}

Status FieldDescriptor::parse_map_field(const std::vector<tparquet::SchemaElement>& t_schemas,
                                        size_t curr_pos, FieldSchema* map_field) {
    // the map definition in parquet:
    // optional group <name> (MAP) {
    //   repeated group map (MAP_KEY_VALUE) {
    //     required <type> key;
    //     optional <type> value;
    //   }
    // }
    // Map value can be optional, the map without values is a SET
    if (curr_pos + 2 >= t_schemas.size()) {
        return Status::InvalidArgument("Map element should have at least three levels");
    }
    auto& map_schema = t_schemas[curr_pos];
    if (map_schema.num_children != 1) {
        return Status::InvalidArgument(
                "Map element should have only one child(name='map', type='MAP_KEY_VALUE')");
    }
    if (is_repeated_node(map_schema)) {
        return Status::InvalidArgument("Map element can't be a repeated schema");
    }
    auto& map_key_value = t_schemas[curr_pos + 1];
    if (!is_group_node(map_key_value) || !is_repeated_node(map_key_value)) {
        return Status::InvalidArgument(
                "the second level in map must be a repeated group(key and value)");
    }
    auto& map_key = t_schemas[curr_pos + 2];
    if (!is_required_node(map_key)) {
        LOG(WARNING) << "Filed " << map_schema.name << " is map type, but with nullable key column";
    }

    if (map_key_value.num_children == 1) {
        // The map with three levels is a SET
        return parse_list_field(t_schemas, curr_pos, map_field);
    }
    if (map_key_value.num_children != 2) {
        // A standard map should have four levels
        return Status::InvalidArgument(
                "the second level in map(MAP_KEY_VALUE) should have two children");
    }
    // standard map
    bool is_optional = is_optional_node(map_schema);
    if (is_optional) {
        map_field->definition_level++;
    }
    map_field->repetition_level++;
    map_field->definition_level++;

    map_field->children.resize(1);
    // map is a repeated node, we should set the `repeated_parent_def_level` of its children as `definition_level`
    set_child_node_level(map_field, map_field->definition_level);
    auto map_kv_field = &map_field->children[0];
    // produce MAP<STRUCT<KEY, VALUE>>
    RETURN_IF_ERROR(parse_struct_field(t_schemas, curr_pos + 1, map_kv_field));

    map_field->name = map_schema.name;
    map_field->data_type = std::make_shared<DataTypeMap>(
            make_nullable(assert_cast<const DataTypeStruct*>(
                                  remove_nullable(map_kv_field->data_type).get())
                                  ->get_element(0)),
            make_nullable(assert_cast<const DataTypeStruct*>(
                                  remove_nullable(map_kv_field->data_type).get())
                                  ->get_element(1)));
    if (is_optional) {
        map_field->data_type = make_nullable(map_field->data_type);
    }
    map_field->field_id = map_schema.__isset.field_id ? map_schema.field_id : -1;

    return Status::OK();
}

Status FieldDescriptor::parse_struct_field(const std::vector<tparquet::SchemaElement>& t_schemas,
                                           size_t curr_pos, FieldSchema* struct_field) {
    // the nested column in parquet, parse group to struct.
    auto& struct_schema = t_schemas[curr_pos];
    bool is_optional = is_optional_node(struct_schema);
    if (is_optional) {
        struct_field->definition_level++;
    }
    auto num_children = struct_schema.num_children;
    struct_field->children.resize(num_children);
    set_child_node_level(struct_field, struct_field->repeated_parent_def_level);
    _next_schema_pos = curr_pos + 1;
    for (int i = 0; i < num_children; ++i) {
        RETURN_IF_ERROR(parse_node_field(t_schemas, _next_schema_pos, &struct_field->children[i]));
    }
    struct_field->name = struct_schema.name;

    struct_field->field_id = struct_schema.__isset.field_id ? struct_schema.field_id : -1;
    DataTypes res_data_types;
    std::vector<String> names;
    for (int i = 0; i < num_children; ++i) {
        res_data_types.push_back(make_nullable(struct_field->children[i].data_type));
        names.push_back(struct_field->children[i].name);
    }
    struct_field->data_type = std::make_shared<DataTypeStruct>(res_data_types, names);
    if (is_optional) {
        struct_field->data_type = make_nullable(struct_field->data_type);
    }
    return Status::OK();
}

int FieldDescriptor::get_column_index(const std::string& column) const {
    for (int32_t i = 0; i < _fields.size(); i++) {
        if (_fields[i].name == column) {
            return i;
        }
    }
    return -1;
}

const FieldSchema* FieldDescriptor::get_column(const std::string& name) const {
    auto it = _name_to_field.find(name);
    if (it != _name_to_field.end()) {
        return it->second;
    }
    throw Exception(Status::InternalError("Name {} not found in FieldDescriptor!", name));
    return nullptr;
}

void FieldDescriptor::get_column_names(std::unordered_set<std::string>* names) const {
    names->clear();
    for (const FieldSchema& f : _fields) {
        names->emplace(f.name);
    }
}

std::string FieldDescriptor::debug_string() const {
    std::stringstream ss;
    ss << "fields=[";
    for (int i = 0; i < _fields.size(); ++i) {
        if (i != 0) {
            ss << ", ";
        }
        ss << _fields[i].debug_string();
    }
    ss << "]";
    return ss.str();
}
#include "common/compile_check_end.h"

} // namespace doris::vectorized
