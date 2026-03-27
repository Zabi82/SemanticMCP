FROM python:3.11-slim

# Install dbt and metricflow
RUN pip install --no-cache-dir \
    dbt-core \
    dbt-trino \
    dbt-metricflow \
    flask \
    flask-cors

# Set working directory
WORKDIR /dbt

# Copy dbt project files
COPY dbt_project.yml .
COPY profiles.yml .
COPY models/ models/

# Copy metricflow API server and helpers
COPY metricflow_server.py .
COPY wait-for-trino.py .

# Copy entrypoint script
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

# Expose metricflow server port
EXPOSE 8087

# Use entrypoint to run dbt models before starting API server
ENTRYPOINT ["/entrypoint.sh"]
