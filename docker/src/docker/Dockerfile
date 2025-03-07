FROM azul/zulu-openjdk:8u352

## Add packages, clean cache, create dirs, create corda user and change ownership
RUN apt-get update && \
    apt-mark hold zulu8-jdk && \
    apt-get -y upgrade && \
    apt-get -y install bash curl unzip && \
    rm -rf /var/lib/apt/lists/* && \
    mkdir -p /opt/corda/cordapps && \
    mkdir -p /opt/corda/persistence && \
    mkdir -p /opt/corda/artemis && \
    mkdir -p /opt/corda/certificates && \
    mkdir -p /opt/corda/drivers && \
    mkdir -p /opt/corda/logs && \
    mkdir -p /opt/corda/bin && \
    mkdir -p /opt/corda/additional-node-infos && \
    mkdir -p /etc/corda && \
    addgroup corda && \
    useradd corda -g corda -m -d /opt/corda && \
    chown -R corda:corda /opt/corda && \
    chown -R corda:corda /etc/corda

ENV CORDAPPS_FOLDER="/opt/corda/cordapps" \
    PERSISTENCE_FOLDER="/opt/corda/persistence" \
    ARTEMIS_FOLDER="/opt/corda/artemis" \
    CERTIFICATES_FOLDER="/opt/corda/certificates" \
    DRIVERS_FOLDER="/opt/corda/drivers" \
    CONFIG_FOLDER="/etc/corda" \
    MY_P2P_PORT=10200 \
    MY_RPC_PORT=10201 \
    MY_RPC_ADMIN_PORT=10202 \
    PATH=$PATH:/opt/corda/bin \
    JVM_ARGS="-XX:+UseG1GC -XX:+UnlockExperimentalVMOptions -XX:+UseCGroupMemoryLimitForHeap " \
    CORDA_ARGS=""

##CORDAPPS FOLDER
VOLUME ["/opt/corda/cordapps"]
##PERSISTENCE FOLDER
VOLUME ["/opt/corda/persistence"]
##ARTEMIS FOLDER
VOLUME ["/opt/corda/artemis"]
##CERTS FOLDER
VOLUME ["/opt/corda/certificates"]
##OPTIONAL JDBC DRIVERS FOLDER
VOLUME ["/opt/corda/drivers"]
##LOG FOLDER
VOLUME ["/opt/corda/logs"]
##ADDITIONAL NODE INFOS FOLDER
VOLUME ["/opt/corda/additional-node-infos"]
##CONFIG LOCATION
VOLUME ["/etc/corda"]

##CORDA JAR
COPY --chown=corda:corda corda.jar /opt/corda/bin/corda.jar
##CONFIG MANIPULATOR JAR
COPY --chown=corda:corda config-exporter.jar /opt/corda/config-exporter.jar
##CONFIG GENERATOR SHELL SCRIPT
COPY --chown=corda:corda generate-config.sh /opt/corda/bin/config-generator
##CORDA RUN SCRIPT
COPY --chown=corda:corda run-corda.sh /opt/corda/bin/run-corda
##BASE CONFIG FOR GENERATOR
COPY --chown=corda:corda starting-node.conf /opt/corda/starting-node.conf

USER "corda"
EXPOSE ${MY_P2P_PORT} ${MY_RPC_PORT} ${MY_RPC_ADMIN_PORT}
WORKDIR /opt/corda
CMD ["run-corda"]
