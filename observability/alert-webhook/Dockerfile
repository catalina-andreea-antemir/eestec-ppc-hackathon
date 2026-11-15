FROM python:3.13.18-slim-bookworm
WORKDIR /app
COPY receiver.py .
USER 10001:10001
EXPOSE 8080
CMD ["python", "-u", "-B", "receiver.py"]
