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
// This file is copied from
// https://github.com/ClickHouse/ClickHouse/blob/master/src/Functions/array/arrayElement.cpp
// and modified by Doris

#pragma once

#include <glog/logging.h>
#include <string.h>

#include <algorithm>
#include <boost/iterator/iterator_facade.hpp>
#include <memory>
#include <ostream>
#include <string>
#include <utility>

#include "common/status.h"
#include "runtime/primitive_type.h"
#include "vec/columns/column.h"
#include "vec/columns/column_array.h"
#include "vec/columns/column_decimal.h"
#include "vec/columns/column_map.h"
#include "vec/columns/column_nullable.h"
#include "vec/columns/column_string.h"
#include "vec/columns/column_struct.h"
#include "vec/columns/column_vector.h"
#include "vec/common/assert_cast.h"
#include "vec/core/block.h"
#include "vec/core/column_numbers.h"
#include "vec/core/column_with_type_and_name.h"
#include "vec/core/columns_with_type_and_name.h"
#include "vec/core/types.h"
#include "vec/data_types/data_type.h"
#include "vec/data_types/data_type_array.h"
#include "vec/data_types/data_type_map.h"
#include "vec/data_types/data_type_nullable.h"
#include "vec/data_types/data_type_number.h"
#include "vec/functions/function.h"
#include "vec/functions/function_helpers.h"

namespace doris {
class FunctionContext;
} // namespace doris

namespace doris::vectorized {
#include "common/compile_check_begin.h"

class FunctionArrayElement : public IFunction {
public:
    /// The count of items in the map may exceed 128(Int8).
    using MapIndiceDataType = DataTypeInt16;

    static constexpr auto name = "element_at";
    static FunctionPtr create() { return std::make_shared<FunctionArrayElement>(); }

    /// Get function name.
    String get_name() const override { return name; }

    bool is_variadic() const override { return false; }

    bool use_default_implementation_for_nulls() const override { return false; }

    size_t get_number_of_arguments() const override { return 2; }

    DataTypePtr get_return_type_impl(const DataTypes& arguments) const override {
        DataTypePtr arg_0 = remove_nullable(arguments[0]);
        DCHECK(arg_0->get_primitive_type() == TYPE_ARRAY || arg_0->get_primitive_type() == TYPE_MAP)
                << "first argument for function: " << name
                << " should be DataTypeArray or DataTypeMap, but it is " << arg_0->get_name();
        if (arg_0->get_primitive_type() == TYPE_ARRAY) {
            DCHECK(is_int_or_bool(arguments[1]->get_primitive_type()))
                    << "second argument for function: " << name
                    << " should be Integer for array element";
            return make_nullable(
                    check_and_get_data_type<DataTypeArray>(arg_0.get())->get_nested_type());
        } else if (arg_0->get_primitive_type() == TYPE_MAP) {
            return make_nullable(
                    check_and_get_data_type<DataTypeMap>(arg_0.get())->get_value_type());
        } else {
            throw doris::Exception(
                    ErrorCode::INVALID_ARGUMENT,
                    fmt::format("element_at only support array and map so far, but got {}",
                                arg_0->get_name()));
        }
    }

    Status execute_impl(FunctionContext* context, Block& block, const ColumnNumbers& arguments,
                        uint32_t result, size_t input_rows_count) const override {
        auto dst_null_column = ColumnUInt8::create(input_rows_count, 0);
        UInt8* dst_null_map = dst_null_column->get_data().data();
        const UInt8* src_null_map = nullptr;
        ColumnsWithTypeAndName args;
        block.replace_by_position(
                arguments[0],
                block.get_by_position(arguments[0]).column->convert_to_full_column_if_const());
        auto col_left = block.get_by_position(arguments[0]);
        if (col_left.column->is_nullable()) {
            auto null_col = check_and_get_column<ColumnNullable>(*col_left.column);
            src_null_map = null_col->get_null_map_column().get_data().data();
            args = {{null_col->get_nested_column_ptr(), remove_nullable(col_left.type),
                     col_left.name},
                    block.get_by_position(arguments[1])};
        } else {
            args = {col_left, block.get_by_position(arguments[1])};
        }
        ColumnPtr res_column = nullptr;
        if (is_column<ColumnMap>(args[0].column.get()) ||
            check_column_const<ColumnMap>(args[0].column.get())) {
            res_column = _execute_map(args, input_rows_count, src_null_map, dst_null_map);
        } else {
            res_column = _execute_nullable(args, input_rows_count, src_null_map, dst_null_map);
        }
        if (!res_column) {
            return Status::RuntimeError("unsupported types for function {}({}, {})", get_name(),
                                        block.get_by_position(arguments[0]).type->get_name(),
                                        block.get_by_position(arguments[1]).type->get_name());
        }
        block.replace_by_position(result,
                                  ColumnNullable::create(res_column, std::move(dst_null_column)));
        return Status::OK();
    }

private:
    //=========================== map element===========================//
    ColumnPtr _get_mapped_idx(const ColumnArray& column,
                              const ColumnWithTypeAndName& argument) const {
        auto right_column = make_nullable(argument.column->convert_to_full_column_if_const());
        const ColumnArray::Offsets64& offsets = column.get_offsets();
        ColumnPtr nested_ptr = make_nullable(column.get_data_ptr());
        size_t rows = offsets.size();
        // prepare return data
        auto matched_indices = ColumnVector<MapIndiceDataType::PType>::create();
        matched_indices->reserve(rows);

        for (size_t i = 0; i < rows; i++) {
            bool matched = false;
            size_t begin = offsets[i - 1];
            size_t end = offsets[i];
            for (size_t j = begin; j < end; j++) {
                if (nested_ptr->compare_at(j, i, *right_column, -1) == 0) {
                    matched_indices->insert_value(
                            cast_set<MapIndiceDataType::FieldType, size_t, false>(j - begin + 1));
                    matched = true;
                    break;
                }
            }

            if (!matched) {
                matched_indices->insert_value(cast_set<MapIndiceDataType::FieldType, size_t, false>(
                        end - begin + 1)); // make indices for null
            }
        }

        return matched_indices;
    }

    template <typename ColumnType>
    ColumnPtr _execute_number(const ColumnArray::Offsets64& offsets, const IColumn& nested_column,
                              const UInt8* arr_null_map, const IColumn& indices,
                              const UInt8* nested_null_map, UInt8* dst_null_map) const {
        const auto& nested_data = reinterpret_cast<const ColumnType&>(nested_column).get_data();

        auto dst_column = nested_column.clone_empty();
        auto& dst_data = reinterpret_cast<ColumnType&>(*dst_column).get_data();
        dst_data.resize(offsets.size());

        // process
        for (size_t row = 0; row < offsets.size(); ++row) {
            size_t off = offsets[row - 1];
            size_t len = offsets[row] - off;
            auto index = indices.get_int(row);
            // array is nullable
            bool null_flag = bool(arr_null_map && arr_null_map[row]);
            // calc index in nested column
            if (!null_flag && index > 0 && index <= len) {
                index += off - 1;
            } else if (!null_flag && index < 0 && -index <= len) {
                index += off + len;
            } else {
                null_flag = true;
            }
            // nested column nullable check
            if (!null_flag && nested_null_map && nested_null_map[index]) {
                null_flag = true;
            }
            // actual data copy
            if (null_flag) {
                dst_null_map[row] = true;
                dst_data[row] = typename ColumnType::value_type();
            } else {
                DCHECK(index >= 0 && index < nested_data.size());
                dst_null_map[row] = false;
                dst_data[row] = nested_data[index];
            }
        }
        return dst_column;
    }

    ColumnPtr _execute_string(const ColumnArray::Offsets64& offsets, const IColumn& nested_column,
                              const UInt8* arr_null_map, const IColumn& indices,
                              const UInt8* nested_null_map, UInt8* dst_null_map) const {
        const auto& src_str_offs =
                reinterpret_cast<const ColumnString&>(nested_column).get_offsets();
        const auto& src_str_chars =
                reinterpret_cast<const ColumnString&>(nested_column).get_chars();

        // prepare return data
        auto dst_column = ColumnString::create();
        auto& dst_str_offs = dst_column->get_offsets();
        dst_str_offs.resize(offsets.size());
        auto& dst_str_chars = dst_column->get_chars();
        dst_str_chars.reserve(src_str_chars.size());

        // process
        for (size_t row = 0; row < offsets.size(); ++row) {
            size_t off = offsets[row - 1];
            size_t len = offsets[row] - off;
            auto index = indices.get_int(row);
            // array is nullable
            bool null_flag = bool(arr_null_map && arr_null_map[row]);
            // calc index in nested column
            if (!null_flag && index > 0 && index <= len) {
                index += off - 1;
            } else if (!null_flag && index < 0 && -index <= len) {
                index += off + len;
            } else {
                null_flag = true;
            }
            // nested column nullable check
            if (!null_flag && nested_null_map && nested_null_map[index]) {
                null_flag = true;
            }
            // actual string copy
            if (!null_flag) {
                DCHECK(index >= 0 && index < src_str_offs.size());
                dst_null_map[row] = false;
                auto element_size = src_str_offs[index] - src_str_offs[index - 1];
                dst_str_offs[row] = dst_str_offs[row - 1] + element_size;
                auto src_string_pos = src_str_offs[index - 1];
                auto dst_string_pos = dst_str_offs[row - 1];
                dst_str_chars.resize(dst_string_pos + element_size);
                memcpy(&dst_str_chars[dst_string_pos], &src_str_chars[src_string_pos],
                       element_size);
            } else {
                dst_null_map[row] = true;
                dst_str_offs[row] = dst_str_offs[row - 1];
            }
        }
        return dst_column;
    }

    ColumnPtr _execute_map(const ColumnsWithTypeAndName& arguments, size_t input_rows_count,
                           const UInt8* src_null_map, UInt8* dst_null_map) const {
        auto left_column = arguments[0].column->convert_to_full_column_if_const();
        DataTypePtr val_type =
                reinterpret_cast<const DataTypeMap&>(*arguments[0].type).get_value_type();
        const auto& map_column = reinterpret_cast<const ColumnMap&>(*left_column);

        // create column array to find keys
        auto key_arr = ColumnArray::create(map_column.get_keys_ptr(), map_column.get_offsets_ptr());
        auto val_arr =
                ColumnArray::create(map_column.get_values_ptr(), map_column.get_offsets_ptr());

        const auto& offsets = map_column.get_offsets();
        const size_t rows = offsets.size();

        if (rows <= 0) {
            return nullptr;
        }
        if (key_arr->is_nullable()) {
        }
        ColumnPtr matched_indices = _get_mapped_idx(*key_arr, arguments[1]);
        if (!matched_indices) {
            return nullptr;
        }
        DataTypePtr indices_type(std::make_shared<MapIndiceDataType>());
        ColumnWithTypeAndName indices(matched_indices, indices_type, "indices");
        ColumnWithTypeAndName data(std::move(val_arr), std::make_shared<DataTypeArray>(val_type),
                                   "value");
        ColumnsWithTypeAndName args = {data, indices};
        return _execute_nullable(args, input_rows_count, src_null_map, dst_null_map);
    }

    ColumnPtr _execute_common(const ColumnArray::Offsets64& offsets, const IColumn& nested_column,
                              const UInt8* arr_null_map, const IColumn& indices,
                              const UInt8* nested_null_map, UInt8* dst_null_map) const {
        // prepare return data
        auto dst_column = nested_column.clone_empty();
        dst_column->reserve(offsets.size());

        // process
        for (size_t row = 0; row < offsets.size(); ++row) {
            size_t off = offsets[row - 1];
            size_t len = offsets[row] - off;
            auto index = indices.get_int(row);
            // array is nullable
            bool null_flag = bool(arr_null_map && arr_null_map[row]);
            // calc index in nested column
            if (!null_flag && index > 0 && index <= len) {
                index += off - 1;
            } else if (!null_flag && index < 0 && -index <= len) {
                index += off + len;
            } else {
                null_flag = true;
            }
            // nested column nullable check
            if (!null_flag && nested_null_map && nested_null_map[index]) {
                null_flag = true;
            }
            // actual data copy
            if (!null_flag) {
                dst_null_map[row] = false;
                dst_column->insert_from(nested_column, index);
            } else {
                dst_null_map[row] = true;
                dst_column->insert_default();
            }
        }
        return dst_column;
    }

    ColumnPtr _execute_nullable(const ColumnsWithTypeAndName& arguments, size_t input_rows_count,
                                const UInt8* src_null_map, UInt8* dst_null_map) const {
        // check array nested column type and get data
        auto left_column = arguments[0].column->convert_to_full_column_if_const();
        const auto& array_column = assert_cast<const ColumnArray&>(*left_column);
        const auto& offsets = array_column.get_offsets();
        DCHECK(offsets.size() == input_rows_count);
        const UInt8* nested_null_map = nullptr;
        ColumnPtr nested_column = nullptr;
        if (is_column_nullable(array_column.get_data())) {
            const auto& nested_null_column =
                    reinterpret_cast<const ColumnNullable&>(array_column.get_data());
            nested_null_map = nested_null_column.get_null_map_column().get_data().data();
            nested_column = nested_null_column.get_nested_column_ptr();
        } else {
            nested_column = array_column.get_data_ptr();
        }

        ColumnPtr res = nullptr;
        auto left_element_type = remove_nullable(
                assert_cast<const DataTypeArray&>(*remove_nullable(arguments[0].type))
                        .get_nested_type());
        // because we impl use_default_implementation_for_nulls
        // we should handle array index column by-self, and array index should not be nullable.
        auto idx_col = remove_nullable(arguments[1].column);
        // we should dispatch branch according to data type rather than column type
        switch (left_element_type->get_primitive_type()) {
        case TYPE_DATE: {
            res = _execute_number<ColumnDate>(offsets, *nested_column, src_null_map, *idx_col,
                                              nested_null_map, dst_null_map);
            break;
        }
        case TYPE_DATETIME: {
            res = _execute_number<ColumnDateTime>(offsets, *nested_column, src_null_map, *idx_col,
                                                  nested_null_map, dst_null_map);
            break;
        }
        case TYPE_DATEV2: {
            res = _execute_number<ColumnDateV2>(offsets, *nested_column, src_null_map, *idx_col,
                                                nested_null_map, dst_null_map);
            break;
        }
        case TYPE_DATETIMEV2: {
            res = _execute_number<ColumnDateTimeV2>(offsets, *nested_column, src_null_map, *idx_col,
                                                    nested_null_map, dst_null_map);
            break;
        }
        case TYPE_BOOLEAN: {
            res = _execute_number<ColumnUInt8>(offsets, *nested_column, src_null_map, *idx_col,
                                               nested_null_map, dst_null_map);
            break;
        }
        case TYPE_TINYINT: {
            res = _execute_number<ColumnInt8>(offsets, *nested_column, src_null_map, *idx_col,
                                              nested_null_map, dst_null_map);
            break;
        }
        case TYPE_SMALLINT: {
            res = _execute_number<ColumnInt16>(offsets, *nested_column, src_null_map, *idx_col,
                                               nested_null_map, dst_null_map);
            break;
        }
        case TYPE_INT: {
            res = _execute_number<ColumnInt32>(offsets, *nested_column, src_null_map, *idx_col,
                                               nested_null_map, dst_null_map);
            break;
        }
        case TYPE_BIGINT: {
            res = _execute_number<ColumnInt64>(offsets, *nested_column, src_null_map, *idx_col,
                                               nested_null_map, dst_null_map);
            break;
        }
        case TYPE_LARGEINT: {
            res = _execute_number<ColumnInt128>(offsets, *nested_column, src_null_map, *idx_col,
                                                nested_null_map, dst_null_map);
            break;
        }
        case TYPE_FLOAT: {
            res = _execute_number<ColumnFloat32>(offsets, *nested_column, src_null_map, *idx_col,
                                                 nested_null_map, dst_null_map);
            break;
        }
        case TYPE_DOUBLE: {
            res = _execute_number<ColumnFloat64>(offsets, *nested_column, src_null_map, *idx_col,
                                                 nested_null_map, dst_null_map);
            break;
        }
        case TYPE_DECIMAL32: {
            res = _execute_number<ColumnDecimal32>(offsets, *nested_column, src_null_map, *idx_col,
                                                   nested_null_map, dst_null_map);
            break;
        }
        case TYPE_DECIMAL64: {
            res = _execute_number<ColumnDecimal64>(offsets, *nested_column, src_null_map, *idx_col,
                                                   nested_null_map, dst_null_map);
            break;
        }
        case TYPE_DECIMAL256: {
            res = _execute_number<ColumnDecimal256>(offsets, *nested_column, src_null_map, *idx_col,
                                                    nested_null_map, dst_null_map);
            break;
        }
        case TYPE_DECIMALV2: {
            res = _execute_number<ColumnDecimal128V2>(offsets, *nested_column, src_null_map,
                                                      *idx_col, nested_null_map, dst_null_map);
            break;
        }
        case TYPE_DECIMAL128I: {
            res = _execute_number<ColumnDecimal128V3>(offsets, *nested_column, src_null_map,
                                                      *idx_col, nested_null_map, dst_null_map);
            break;
        }
        case TYPE_CHAR:
        case TYPE_VARCHAR:
        case TYPE_STRING: {
            res = _execute_string(offsets, *nested_column, src_null_map, *idx_col, nested_null_map,
                                  dst_null_map);
            break;
        }
        default: {
            res = _execute_common(offsets, *nested_column, src_null_map, *idx_col, nested_null_map,
                                  dst_null_map);
        }
        }
        return res;
    }
};

#include "common/compile_check_end.h"
} // namespace doris::vectorized
