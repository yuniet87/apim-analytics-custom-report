/*
 *  Copyright (c) 2020, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.analytics.apim.custompdf;

import io.siddhi.core.SiddhiAppRuntime;
import io.siddhi.core.SiddhiManager;
import io.siddhi.core.event.Event;
import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.pdfbox.exceptions.COSVisitorException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.edit.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.xobject.PDJpeg;
import org.wso2.analytics.apim.rest.api.report.api.ReportGenerator;
import org.wso2.analytics.apim.rest.api.report.exception.PDFReportException;
import org.wso2.analytics.apim.rest.api.report.impl.ReportApiServiceImpl;
import org.wso2.analytics.apim.rest.api.report.reportgen.DefaultReportGeneratorImpl;
import org.wso2.analytics.apim.rest.api.report.reportgen.model.RowEntry;
import org.wso2.analytics.apim.rest.api.report.reportgen.model.TableData;
// import org.wso2.analytics.apim.rest.api.report.reportgen.util.ReportGeneratorUtil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Custom PDF generator.
 */
public class CustomPDFGenerator implements ReportGenerator {

    private static final float ROW_HEIGHT = 25;
    private static final float CELL_PADDING = 5;
    private static final float CELL_MARGIN = 40; // margin on left side;
    private static final float TABLE_WIDTH = 750;
    private static final float TABLE_TOP_Y = 480;

    // Font configuration
    private static final PDFont TEXT_FONT = PDType1Font.HELVETICA;
    private static final float FONT_SIZE = 9;
    private static final float RECORD_COUNT_PER_PAGE = 15;

    private static final Log log = LogFactory.getLog(DefaultReportGeneratorImpl.class);
    private static final String REQUEST_SUMMARY_MONTHLY_APP_NAME = "/APIMTopAppUsersReport.siddhi";
    private List<Integer> recordsPerPageList;
    private TableData table;
    private PDDocument document;
    private Map<Integer, PDPage> pageMap = new HashMap<>();
    private String period;
    private int numOfPages;
    private final String[] months = { "Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio", "Julio", "Agosto",
            "Septiembre", "Octubre", "Noviembre", "Diciembre" };
    private SiddhiAppRuntime siddhiAppRuntime = null;
    private long totalRequestCount;

    /**
     * The default implementation of Monthly request report.
     * 
     * @param year         year of the report.
     * @param month        month of the report.
     * @param tenantDomain
     */
    public CustomPDFGenerator(String year, String month, String tenantDomain) throws IOException {

        this.table = getRecordsFromAggregations(year, month, tenantDomain);
        if (table.getRows().size() > 0) {
            String[] columnHeaders = { "#", "Nombre de la API", "Versión", "Nombre de la Aplicación", "Usuario",
                    "Cantidad de peticiones" };
            table.setColumnHeaders(columnHeaders);
            String monthName = months[Integer.parseInt(month) - 1];
            this.period = monthName + " " + year;
            this.numOfPages = getNumberOfPages(table.getRows().size());
            this.document = initializePages();
            this.recordsPerPageList = getRecordsPerPage(table.getRows().size());
        }
    }

    private void initializeSiddhiAPPRuntime() throws IOException {
        InputStream inputStream = DefaultReportGeneratorImpl.class
                .getResourceAsStream(REQUEST_SUMMARY_MONTHLY_APP_NAME);
        String siddhiApp = IOUtils.toString(inputStream, StandardCharsets.UTF_8);
        SiddhiManager siddhiManager = new SiddhiManager();
        siddhiAppRuntime = siddhiManager.createSiddhiAppRuntime(siddhiApp);
        siddhiAppRuntime.start();
    }

    private PDDocument initializePages() {

        PDDocument document = new PDDocument();
        for (int i = 1; i <= numOfPages; i++) {
            PDPage nextPage = new PDPage(PDPage.PAGE_SIZE_A4);
            nextPage.setRotation(90);
            document.addPage(nextPage);
            pageMap.put(i, nextPage);
        }
        return document;
    }

    @Override
    public InputStream generateMonthlyRequestSummaryPDF() throws PDFReportException {

        if (table.getRows().size() == 0) {
            return null;
        }
        log.debug("Starting to generate PDF.");
        PDPageContentStream contentStream = null;
        try {
            contentStream = new PDPageContentStream(document, pageMap.get(1), true, false);
            contentStream.concatenate2CTM(0, 1, -1, 0, pageMap.get(1).getMediaBox().getWidth(), 0);
            // ReportGeneratorUtil.insertLogo(document, contentStream);
            insertPageNumber(contentStream, pageMap.get(1), 1);
            insertReportTitleToHeader(contentStream, "Resumen de uso mensual");
            insertReportTimePeriodToHeader(contentStream, period);
            insertReportGeneratedTimeToHeader(contentStream);
            contentStream.close();

            float[] columnWidths = { 40, 160, 70, 160, 160, 160 };
            drawTableGrid(document, pageMap, recordsPerPageList, columnWidths, table.getRows().size());
            writeRowsContent(table.getColumnHeaders(), columnWidths, document, pageMap, table.getRows());

            // write total request count to the header.
            PDPageContentStream contentStreamForInitialPage = new PDPageContentStream(document, pageMap.get(1), true,
                    false);
            insertTotalRequestCountToHeader(contentStreamForInitialPage, totalRequestCount);
            contentStreamForInitialPage.close();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            document.close();
            log.debug("PDF generation complete.");
            return new ByteArrayInputStream(out.toByteArray());
        } catch (IOException | COSVisitorException e) {
            throw new PDFReportException("Error during generating monthly request summary report.", e);
        }
    }

    private TableData getRecordsFromAggregations(String year, String month, String apiCreatorTenantDomain)
            throws IOException {

        TableData table = new TableData();
        String date = year + "-" + month;
        String requestCountQuery = "from ApiUserPerAppAgg on apiCreatorTenantDomain==" + "\'" + apiCreatorTenantDomain
                + "\'" + " within '" + date + "-** **:**:**' per \"months\" select apiName, apiVersion, "
                + "applicationName, applicationOwner, sum(totalRequestCount) as " + "RequestCount group by "
                + "apiName, apiVersion, applicationName, applicationOwner order by RequestCount desc";

        initializeSiddhiAPPRuntime();
        Event[] events = siddhiAppRuntime.query(requestCountQuery);
        siddhiAppRuntime.shutdown();
        if (events == null) {
            return table; // no data found
        }
        List<RowEntry> rowData = new ArrayList<>();
        int recordNumber = 1;
        for (Event event : events) {
            RowEntry entry = new RowEntry();
            entry.setEntry(recordNumber + ")");
            entry.setEntry(event.getData(0).toString());
            entry.setEntry(event.getData(1).toString());
            entry.setEntry(event.getData(2).toString());
            entry.setEntry(event.getData(3).toString().split("@")[0]);
            entry.setEntry(event.getData(4).toString());
            totalRequestCount += (Long) event.getData(4);
            rowData.add(entry);
            table.setRows(rowData);
            recordNumber += 1;
        }
        return table;
    }

    /**
     * Inserts logo onto the top right of the page.
     * 
     * @param document
     * @param contentStream
     * @throws IOException
     */
    public static void insertLogo(PDDocument document, PDPageContentStream contentStream) throws IOException {

        InputStream in = ReportApiServiceImpl.class.getResourceAsStream("/wso2-logo.jpg");
        PDJpeg img = new PDJpeg(document, in);
        contentStream.drawImage(img, 375, 755);
    }

    /**
     * Inserts page number onto the bottom center of the page.
     * 
     * @param contentStream content stream of the page.
     * @param pageNumber    page number.
     * @throws IOException
     */
    public static void insertPageNumber(PDPageContentStream contentStream, PDPage page, int pageNumber)
            throws IOException {

        contentStream.setFont(PDType1Font.HELVETICA_BOLD, FONT_SIZE);
        contentStream.beginText();
        contentStream.moveTextPositionByAmount((page.getMediaBox().getHeight() / 2),
                (PDPage.PAGE_SIZE_A4.getLowerLeftY()) + ROW_HEIGHT);
        contentStream.drawString(pageNumber + "");
        contentStream.endText();
    }

    /**
     *
     * @param contentStream content stream of the page.
     * @param positionX     x-axis position.
     * @param positionY     y-axis position.
     * @param text          the content to write.
     * @throws IOException
     */
    public static void writeContent(PDPageContentStream contentStream, float positionX, float positionY, String text)
            throws IOException {

        contentStream.beginText();
        contentStream.moveTextPositionByAmount(positionX, positionY);
        contentStream.drawString(text != null ? text : "");
        contentStream.endText();
    }

    /**
     * Inserts title to the page.
     * 
     * @param contentStream
     * @param title
     * @throws IOException
     */
    public static void insertReportTitleToHeader(PDPageContentStream contentStream, String title) throws IOException {

        contentStream.setFont(PDType1Font.HELVETICA_BOLD, 16);
        writeContent(contentStream, CELL_MARGIN, 550, title);
    }

    /**
     * Inserts report period to the page.
     * 
     * @param contentStream content stream of the page.
     * @param period        the time duration which should be printed below the
     *                      title.
     * @throws IOException
     */
    public static void insertReportTimePeriodToHeader(PDPageContentStream contentStream, String period)
            throws IOException {

        contentStream.setFont(PDType1Font.HELVETICA_BOLD, 14);
        writeContent(contentStream, CELL_MARGIN, 530, period);
    }

    /**
     * Inserts report generated time.
     * 
     * @param contentStream content stream of the page.
     * @throws IOException
     */
    public static void insertReportGeneratedTimeToHeader(PDPageContentStream contentStream) throws IOException {

        SimpleDateFormat formato = new SimpleDateFormat("EEEE, d 'de' MMMM 'de' yyyy 'a las' hh:mm:ss a",
                Locale.forLanguageTag("es-ES"));

        contentStream.setFont(PDType1Font.HELVETICA_BOLD, FONT_SIZE);
        writeContent(contentStream, CELL_MARGIN, 510, "Reporte generado el : " + formato.format(new Date()));
    }

    /**
     * Draws a table.
     * 
     * @param document           document to draw the table.
     * @param pageMap            map of page objects against page numbers.
     * @param recordsPerPageList a list of integers with number of records for each
     *                           page.
     * @param columnWidths       widths of the columns.
     * @param numberOfRows       total number of rows.
     * @throws IOException
     */
    public static void drawTableGrid(PDDocument document, Map<Integer, PDPage> pageMap,
            List<Integer> recordsPerPageList, float[] columnWidths, int numberOfRows) throws IOException {

        float nextY = TABLE_TOP_Y;

        // draw horizontal lines
        int currentPageNum = 1;
        PDPageContentStream contentStream = new PDPageContentStream(document, pageMap.get(currentPageNum), true, false);
        int rowNum = 0;
        for (int i = 0; i <= numberOfRows + 1; i++) {
            contentStream.drawLine(CELL_MARGIN, nextY, CELL_MARGIN + TABLE_WIDTH, nextY);
            nextY -= ROW_HEIGHT;
            if (rowNum > RECORD_COUNT_PER_PAGE) {
                contentStream.close();
                currentPageNum++;
                contentStream = new PDPageContentStream(document, pageMap.get(currentPageNum), true, false);
                contentStream.concatenate2CTM(0, 1, -1, 0, pageMap.get(currentPageNum).getMediaBox().getWidth(), 0);
                insertPageNumber(contentStream, pageMap.get(currentPageNum), currentPageNum);
                // insertLogo(document, contentStream);
                nextY = TABLE_TOP_Y;
                rowNum = 0;
                numberOfRows++; // at each new page add one more horizontal line
            }
            rowNum++;
        }

        contentStream.close();

        // draw vertical lines
        for (int k = 1; k <= pageMap.size(); k++) {
            float tableYLength = (ROW_HEIGHT * (recordsPerPageList.get(k - 1)));
            float tableBottomY = TABLE_TOP_Y - tableYLength;
            if (k == 1) {
                tableBottomY -= ROW_HEIGHT;
            }
            float nextX = CELL_MARGIN;

            contentStream = new PDPageContentStream(document, pageMap.get(k), true, false);
            for (float columnWidth : columnWidths) {
                contentStream.drawLine(nextX, TABLE_TOP_Y, nextX, tableBottomY);
                nextX += columnWidth;
            }
            contentStream.drawLine(CELL_MARGIN + TABLE_WIDTH, TABLE_TOP_Y, CELL_MARGIN + TABLE_WIDTH, tableBottomY);
            contentStream.close();
        }
    }

    /**
     * Prints a table with column headers and data.
     * 
     * @param columnHeaders the table column headers.
     * @param columnWidths  widths of each column.
     * @param document      the document.
     * @param pageMap       page map with each page object stored against each page
     *                      index.
     * @param rowEntries    list of rows.
     * @throws IOException
     */
    public static void writeRowsContent(String[] columnHeaders, float[] columnWidths, PDDocument document,
            Map<Integer, PDPage> pageMap, List<RowEntry> rowEntries) throws IOException {

        float startX = CELL_MARGIN + CELL_PADDING; // space between entry and the column line
        float startY = TABLE_TOP_Y - (ROW_HEIGHT / 2)
                - ((TEXT_FONT.getFontDescriptor().getFontBoundingBox().getHeight() / 1000 * FONT_SIZE) / 4);

        PDPageContentStream contentStream = new PDPageContentStream(document, pageMap.get(1), true, false);

        // write table column headers
        writeColumnHeader(contentStream, columnWidths, startX, startY, columnHeaders);

        PDPageContentStream contentStreamForData = new PDPageContentStream(document, pageMap.get(1), true, false);
        contentStreamForData.setFont(TEXT_FONT, FONT_SIZE);
        startY -= ROW_HEIGHT;
        startX = CELL_MARGIN + CELL_PADDING;

        int currentPageNum = 1;
        int rowNum = 0;
        // write content
        for (RowEntry entry : rowEntries) {
            rowNum += 1;
            if (rowNum > RECORD_COUNT_PER_PAGE) {
                contentStreamForData.close();
                currentPageNum += 1;
                contentStreamForData = new PDPageContentStream(document, pageMap.get(currentPageNum), true, false);
                contentStream.concatenate2CTM(0, 1, -1, 0, pageMap.get(currentPageNum).getMediaBox().getWidth(), 0);
                contentStreamForData.setFont(TEXT_FONT, FONT_SIZE);
                startY = TABLE_TOP_Y - (ROW_HEIGHT / 2)
                        - ((TEXT_FONT.getFontDescriptor().getFontBoundingBox().getHeight() / 1000 * FONT_SIZE) / 4);
                startX = CELL_MARGIN + CELL_PADDING;
                rowNum = 1;
            }
            writeToRow(contentStreamForData, columnWidths, startX, startY, entry);
            startY -= ROW_HEIGHT;
            startX = CELL_MARGIN + CELL_PADDING;
        }
        contentStreamForData.close();
    }

    /**
     * Writes the column header.
     * 
     * @param contentStream content stream of the page.
     * @param columnWidths  widths of each column.
     * @param positionX     x-axis position
     * @param positionY     y-axis position
     * @param content       data to write in column header.
     * @throws IOException
     */
    public static void writeColumnHeader(PDPageContentStream contentStream, float[] columnWidths, float positionX,
            float positionY, String[] content) throws IOException {

        contentStream.setFont(PDType1Font.HELVETICA_BOLD, FONT_SIZE);
        for (int i = 0; i < columnWidths.length; i++) {
            writeContent(contentStream, positionX, positionY, content[i]);
            positionX += columnWidths[i];
        }
        contentStream.setFont(TEXT_FONT, FONT_SIZE);
        contentStream.close();
    }

    /**
     * Writes a row.
     * 
     * @param contentStream content stream of the page.
     * @param columnWidths  widths of each column.
     * @param positionX     x-axis position
     * @param positionY     y-axis position
     * @param entry         row data.
     * @throws IOException
     */
    public static void writeToRow(PDPageContentStream contentStream, float[] columnWidths, float positionX,
            float positionY, RowEntry entry) throws IOException {

        for (int i = 0; i < columnWidths.length; i++) {
            writeContent(contentStream, positionX, positionY, entry.getEntries().get(i));
            positionX += columnWidths[i];
        }
    }

    /**
     * Inserts total request count of the report on the header.
     * 
     * @param contentStream     content stream of the page.
     * @param totalRequestCount total aggregated count.
     * @throws IOException
     */
    public static void insertTotalRequestCountToHeader(PDPageContentStream contentStream, Long totalRequestCount)
            throws IOException {

        contentStream.setFont(PDType1Font.HELVETICA_BOLD, FONT_SIZE);
        writeContent(contentStream, CELL_MARGIN, 490,
                "Total de peticiones realizadas : " + totalRequestCount.toString());
    }

    /**
     * Returns the number of pages in the document.
     * 
     * @param numberOfRows number of records.
     * @return number of pages in the document.
     */
    public static int getNumberOfPages(int numberOfRows) {

        return (int) Math.ceil(numberOfRows / RECORD_COUNT_PER_PAGE);
    }

    /**
     * Get List of integers with the number of records in each page.
     * 
     * @param numberOfRows total number of rows across the document.
     * @return list of integers with the number of records. Each index represents
     *         the page number - 1.
     */
    public static List<Integer> getRecordsPerPage(int numberOfRows) {

        int numOfPages = (int) Math.ceil(numberOfRows / RECORD_COUNT_PER_PAGE);
        List<Integer> recordCountPerPage = new ArrayList<>();

        int remainingRows = numberOfRows;

        if (numberOfRows < RECORD_COUNT_PER_PAGE) {
            recordCountPerPage.add(numberOfRows);
            return recordCountPerPage;
        } else {
            for (int i = 0; i < numOfPages; i++) {
                if (remainingRows >= RECORD_COUNT_PER_PAGE) {
                    recordCountPerPage.add((int) RECORD_COUNT_PER_PAGE);
                    remainingRows -= RECORD_COUNT_PER_PAGE;
                } else {
                    recordCountPerPage.add(remainingRows);
                }
            }
        }
        return recordCountPerPage;
    }
}
