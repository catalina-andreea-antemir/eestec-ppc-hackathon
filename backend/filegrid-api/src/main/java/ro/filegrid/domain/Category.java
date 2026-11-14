package ro.filegrid.domain;

/**
 * Cele 4 categorii de fișiere (contract: schema {@code Category}, planul v4 secțiunea 4).
 * Numele constantelor sunt exact valorile din API, din YAML-ul de politică și din coloanele {@code category}
 * ale bazei FileGridMeta ({@code CK_files_category}).
 */
public enum Category {
    /** Lot de citiri de contor (1–50 KB), intră doar prin {@code POST /meter-readings}. */
    METER_READING,
    /** Înregistrare de avarie (1–10 MB), cu zonă, stație și moment. */
    OUTAGE_RECORD,
    /** Document de racordare (date personale), criptat AES-GCM înainte de storage. */
    CONNECTION_DOC,
    /** Video de inspecție de la dronă (100 MB – 1 GB+), doar prin upload în bucăți. */
    INSPECTION_VIDEO
}
