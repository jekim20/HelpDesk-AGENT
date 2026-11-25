FROM python:3.12-alpine
WORKDIR /monitoring
COPY alert_sink.py ./alert_sink.py
USER 10001:10001
EXPOSE 8082
CMD ["python", "-u", "alert_sink.py"]
