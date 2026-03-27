#!/usr/bin/env python3
"""
Lightweight Flask API wrapper for dbt metricflow CLI.
Provides REST endpoints for querying metrics via metricflow commands.
"""

from flask import Flask, request, jsonify
from flask_cors import CORS
import subprocess
import json
import os
from decimal import Decimal, InvalidOperation

app = Flask(__name__)
CORS(app)

DBT_PROJECT_DIR = "/dbt"

def run_mf_command(args):
    """Execute metricflow CLI command and return output"""
    try:
        result = subprocess.run(
            ["mf"] + args,
            cwd=DBT_PROJECT_DIR,
            capture_output=True,
            text=True,
            timeout=30
        )
        return {
            "success": result.returncode == 0,
            "stdout": result.stdout,
            "stderr": result.stderr,
            "returncode": result.returncode
        }
    except subprocess.TimeoutExpired:
        return {
            "success": False,
            "error": "Command timed out after 30 seconds"
        }
    except Exception as e:
        return {
            "success": False,
            "error": str(e)
        }

@app.route('/health', methods=['GET'])
def health():
    """Health check endpoint"""
    return jsonify({"status": "healthy", "service": "metricflow-api"})

@app.route('/api/v1/metrics', methods=['GET'])
def list_metrics():
    """List all available metrics"""
    result = run_mf_command(["list", "metrics"])
    if result["success"]:
        output = result["stdout"]
        metrics = []
        
        # Extract metric lines (format: • metric_name: dimension1, dimension2, ...)
        # Return only names to keep response small
        for line in output.split('\n'):
            if line.strip().startswith('•'):
                parts = line.strip()[2:].split(':', 1)
                if parts:
                    metric_name = parts[0].strip()
                    if metric_name:
                        metrics.append({"name": metric_name})
        
        return jsonify({
            "metrics": metrics,
            "count": len(metrics)
        })
    return jsonify({"error": result.get("stderr", result.get("error"))}), 500

@app.route('/api/v1/metrics/<metric_name>', methods=['GET'])
def get_metric_definition(metric_name):
    """Get metric definition"""
    result = run_mf_command(["list", "metrics", "--search", metric_name])
    if result["success"]:
        # Parse the output to extract metric info
        output = result["stdout"]
        metric_info = None
        
        # Extract metric line (format: • metric_name: dimension1, dimension2, ...)
        for line in output.split('\n'):
            if line.strip().startswith('•'):
                parts = line.strip()[2:].split(':', 1)
                if len(parts) == 2:
                    name = parts[0].strip()
                    if name == metric_name:
                        dimensions_str = parts[1].strip()
                        dimensions = [d.strip() for d in dimensions_str.split(',')]
                        metric_info = {
                            "name": name,
                            "dimensions": dimensions
                        }
                        break
        
        if metric_info:
            return jsonify({
                "metric": metric_name,
                "definition": metric_info
            })
        return jsonify({"error": f"Metric '{metric_name}' not found"}), 404
    return jsonify({"error": result.get("stderr", result.get("error"))}), 500

@app.route('/api/v1/metrics/<metric_name>/dimensions', methods=['GET'])
def list_dimensions(metric_name):
    """List dimensions for a metric"""
    result = run_mf_command(["list", "dimensions", "--metrics", metric_name])
    if result["success"]:
        # Parse the output to extract dimension names
        output = result["stdout"]
        dimensions = []
        
        # Extract dimension lines (format: • dimension_name)
        for line in output.split('\n'):
            if line.strip().startswith('•'):
                dimension_name = line.strip()[2:].strip()
                dimensions.append(dimension_name)
        
        return jsonify({
            "metric": metric_name,
            "dimensions": dimensions,
            "count": len(dimensions)
        })
    return jsonify({"error": result.get("stderr", result.get("error"))}), 500

@app.route('/api/v1/query', methods=['POST'])
def query_metric():
    """Query a metric with dimensions and filters"""
    data = request.json
    metric_name = data.get('metric_name')
    dimensions = data.get('dimensions', [])
    show_sql = data.get('show_sql', False)  # Option to return SQL query
    use_direct_sql = data.get('use_direct_sql', True)  # Option to execute SQL directly for full precision
    
    if not metric_name:
        return jsonify({"error": "metric_name is required"}), 400
    
    # Build mf query command
    cmd_args = ["query", "--metrics", metric_name]
    
    # Add --decimals flag for full precision (default to 2 decimal places for currency)
    cmd_args.extend(["--decimals", "2"])
    
    if dimensions:
        cmd_args.extend(["--group-by", ",".join(dimensions)])
    
    # Handle ordering
    # Note: metricflow CLI --order defaults to ASC
    # For DESC, we need to request more rows, reverse, then limit
    order_by_col = data.get('order_by')
    order_direction = data.get('order_direction', 'DESC')  # Default to DESC for business queries
    requested_limit = data.get('limit')
    
    if order_by_col:
        cmd_args.extend(["--order", order_by_col])
        # If DESC requested, don't apply limit to CLI (we'll limit after reversing)
        if order_direction.upper() == 'DESC':
            # Don't add limit to CLI, we'll reverse and limit in post-processing
            pass
        elif requested_limit:
            cmd_args.extend(["--limit", str(requested_limit)])
    elif requested_limit:
        cmd_args.extend(["--limit", str(requested_limit)])
    
    # If use_direct_sql is True, get SQL and execute it directly via Trino for full precision
    if use_direct_sql:
        # First get the SQL using --explain
        explain_args = cmd_args + ["--explain"]
        explain_result = run_mf_command(explain_args)
        
        if explain_result["success"]:
            # Extract SQL from explain output
            sql_query = extract_sql_from_explain(explain_result["stdout"])
            
            if sql_query:
                # Modify SQL for DESC ordering if needed
                if order_by_col and order_direction.upper() == 'DESC':
                    # Replace ORDER BY ... ASC with ORDER BY ... DESC
                    sql_query = sql_query.replace(f"ORDER BY {order_by_col} ASC", 
                                                  f"ORDER BY {order_by_col} DESC")
                    sql_query = sql_query.replace(f"ORDER BY {order_by_col}\n", 
                                                  f"ORDER BY {order_by_col} DESC\n")
                
                # Add LIMIT if specified and not already in query
                if requested_limit and "LIMIT" not in sql_query.upper():
                    sql_query = sql_query.rstrip() + f"\nLIMIT {requested_limit}"
                
                # Execute SQL directly via Trino (would need Trino connection)
                # For now, fall back to metricflow CLI parsing
                # TODO: Add Trino direct execution for full precision
                pass
    
    # If show_sql requested, add --explain flag to get SQL
    if show_sql:
        cmd_args.append("--explain")
    
    result = run_mf_command(cmd_args)
    
    if result["success"]:
        output = result["stdout"]
        
        # If show_sql requested, extract and return SQL
        if show_sql:
            sql_query = extract_sql_from_explain(output)
            
            return jsonify({
                "metric": metric_name,
                "dimensions": dimensions,
                "sql": sql_query,
                "raw_output": output
            })
        
        # Parse the table output into structured data
        rows = []
        headers = []
        
        # Filter out spinner/progress lines and parse table
        lines = output.split('\n')
        table_started = False
        
        for line in lines:
            stripped = line.strip()
            
            # Skip empty lines, spinner chars, INFO/ERROR messages
            if not stripped or stripped.startswith('✔') or stripped.startswith('⠸') or \
               stripped.startswith('⠙') or stripped.startswith('⠹') or stripped.startswith('⠴') or \
               stripped.startswith('⠦') or stripped.startswith('⠧') or stripped.startswith('⠇') or \
               stripped.startswith('⠏') or stripped.startswith('⠋') or stripped.startswith('⠙') or \
               stripped.startswith('⠚') or stripped.startswith('⠞') or stripped.startswith('⠖') or \
               stripped.startswith('⠦') or stripped.startswith('⠴') or stripped.startswith('⠲') or \
               stripped.startswith('⠳') or stripped.startswith('⠓') or 'INFO:' in stripped or \
               'Initiating' in stripped or 'query…' in stripped:
                continue
            
            # Look for separator line (dashes)
            if '---' in stripped or '━' in stripped:
                table_started = True
                continue
            
            # Parse header (before separator)
            if not table_started and not headers:
                # This should be the header line
                headers = stripped.split()
                if headers:
                    continue
            
            # Parse data rows (after separator)
            if table_started and stripped:
                # Handle multi-word values (e.g., "UNITED STATES")
                # Strategy: Split from the right, taking the last N-1 columns as numbers
                # and everything else as the first column (dimension name)
                cells = stripped.split()
                if cells and len(cells) >= len(headers):
                    row_dict = {}
                    # Last columns are numeric values (one per metric)
                    num_value_cols = len(headers) - 1  # All columns except first are values
                    
                    # First column is the dimension (may be multi-word)
                    dimension_parts = cells[:-num_value_cols] if num_value_cols > 0 else cells
                    dimension_value = ' '.join(dimension_parts)
                    row_dict[headers[0]] = dimension_value
                    
                    # Remaining columns are metric values
                    for i, header in enumerate(headers[1:], start=1):
                        value = cells[-(num_value_cols - i + 1)]
                        row_dict[header] = value
                        # Add formatted version for numeric values (preserve full precision)
                        try:
                            # Use Decimal to preserve precision from scientific notation
                            dec_value = Decimal(value)
                            
                            # Format large numbers with commas
                            if abs(dec_value) >= 1000:
                                # Check if it's effectively a whole number (no significant decimal part)
                                if dec_value % 1 == 0:
                                    # Whole number - format without decimals
                                    row_dict[f"{header}_formatted"] = f"{int(dec_value):,}"
                                else:
                                    # Has decimals - format with 2 decimal places
                                    row_dict[f"{header}_formatted"] = f"{dec_value:,.2f}"
                            else:
                                # Small numbers - keep as-is or format with decimals
                                if dec_value % 1 == 0:
                                    row_dict[f"{header}_formatted"] = str(int(dec_value))
                                else:
                                    row_dict[f"{header}_formatted"] = f"{dec_value:.2f}"
                        except (ValueError, InvalidOperation):
                            # Not a number, skip formatting
                            pass
                    rows.append(row_dict)
        
        # Post-process: Reverse order if DESC requested (metricflow CLI only supports ASC)
        if order_by_col and order_direction.upper() == 'DESC':
            rows.reverse()
            # Apply limit after reversing
            if requested_limit and len(rows) > requested_limit:
                rows = rows[:requested_limit]
        
        return jsonify({
            "metric": metric_name,
            "dimensions": dimensions,
            "columns": headers,
            "rows": rows,
            "row_count": len(rows)
        })
    
    # Extract meaningful error from stdout (metricflow writes errors there, not stderr)
    stdout = result.get("stdout", "")
    stderr = result.get("stderr", "")
    # Strip keyring noise from stderr
    clean_stderr = "\n".join(l for l in stderr.splitlines() if "keyring" not in l and "INFO:" not in l).strip()
    # Pull the actual error lines from stdout
    error_lines = [l.strip() for l in stdout.splitlines() if l.strip() and
                   not l.strip().startswith("⠸") and "INFO:" not in l and "keyring" not in l]
    error_msg = "\n".join(error_lines) if error_lines else (clean_stderr or "Unknown metricflow error")
    return jsonify({"error": error_msg}), 500

def extract_sql_from_explain(output):
    """Extract SQL query from metricflow explain output"""
    sql_query = None
    in_sql_block = False
    sql_lines = []
    
    for line in output.split('\n'):
        if 'SELECT' in line.upper() and not in_sql_block:
            in_sql_block = True
            sql_lines.append(line)
        elif in_sql_block:
            # Continue collecting SQL until we hit a blank line or non-SQL content
            if line.strip() and not line.startswith('✔') and not line.startswith('⠸'):
                sql_lines.append(line)
            elif sql_lines:
                break
    
    if sql_lines:
        sql_query = '\n'.join(sql_lines)
    
    return sql_query

@app.route('/api/v1/dimensions', methods=['GET'])
def list_all_dimensions():
    """List all available dimensions"""
    result = run_mf_command(["list", "dimensions"])
    if result["success"]:
        # Parse the output to extract dimension names
        output = result["stdout"]
        dimensions = []
        
        # Extract dimension lines (format: • dimension_name)
        for line in output.split('\n'):
            if line.strip().startswith('•'):
                dimension_name = line.strip()[2:].strip()
                dimensions.append(dimension_name)
        
        return jsonify({
            "dimensions": dimensions,
            "count": len(dimensions)
        })
    return jsonify({"error": result.get("stderr", result.get("error"))}), 500

@app.route('/api/v1/explain', methods=['POST'])
def explain_query():
    """Get the SQL query that metricflow would generate without executing it"""
    data = request.json
    metric_name = data.get('metric_name')
    dimensions = data.get('dimensions', [])
    
    if not metric_name:
        return jsonify({"error": "metric_name is required"}), 400
    
    # Build mf query command with --explain flag
    cmd_args = ["query", "--metrics", metric_name, "--explain"]
    
    if dimensions:
        cmd_args.extend(["--group-by", ",".join(dimensions)])
    
    # Add ordering if specified
    order_by_col = data.get('order_by')
    if order_by_col:
        cmd_args.extend(["--order", order_by_col])
    
    # Add limit if specified
    limit = data.get('limit')
    if limit:
        cmd_args.extend(["--limit", str(limit)])
    
    result = run_mf_command(cmd_args)
    
    if result["success"]:
        output = result["stdout"]
        sql_query = extract_sql_from_explain(output)
        
        return jsonify({
            "metric": metric_name,
            "dimensions": dimensions,
            "sql": sql_query,
            "raw_output": output
        })
    stdout = result.get("stdout", "")
    stderr = result.get("stderr", "")
    clean_stderr = "\n".join(l for l in stderr.splitlines() if "keyring" not in l and "INFO:" not in l).strip()
    error_lines = [l.strip() for l in stdout.splitlines() if l.strip() and "INFO:" not in l and "keyring" not in l]
    error_msg = "\n".join(error_lines) if error_lines else (clean_stderr or "Unknown metricflow error")
    return jsonify({"error": error_msg}), 500

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=8087, debug=True)
