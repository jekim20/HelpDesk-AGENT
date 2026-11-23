FROM python:3.12-alpine

WORKDIR /mock
COPY server.py ./server.py
USER 10001:10001
EXPOSE 8081
HEALTHCHECK --interval=5s --timeout=2s --start-period=2s --retries=5 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8081/health', timeout=1)"
ENTRYPOINT ["python", "-u", "/mock/server.py"]
