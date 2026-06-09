const ComponentUtils = {
    API_BASE_URL: 'http://localhost:8080/api',

    async fetchData(endpoint, options = {}) {
        try {
            const response = await fetch(`${this.API_BASE_URL}${endpoint}`, {
                headers: {
                    'Content-Type': 'application/json',
                    ...options.headers
                },
                ...options
            });
            
            if (!response.ok) {
                throw new Error(`HTTP error! status: ${response.status}`);
            }
            
            return await response.json();
        } catch (error) {
            console.error(`Fetch error for ${endpoint}:`, error);
            throw error;
        }
    },

    async postData(endpoint, data) {
        return this.fetchData(endpoint, {
            method: 'POST',
            body: JSON.stringify(data)
        });
    },

    initECharts(domId, option) {
        const dom = document.getElementById(domId);
        if (!dom) {
            console.error(`Element not found: ${domId}`);
            return null;
        }
        
        const chart = echarts.init(dom, 'dark');
        chart.setOption(option);
        
        window.addEventListener('resize', () => {
            chart.resize();
        });
        
        return chart;
    },

    updateTime() {
        const now = new Date();
        const timeStr = now.toLocaleString('zh-CN', {
            year: 'numeric',
            month: '2-digit',
            day: '2-digit',
            hour: '2-digit',
            minute: '2-digit',
            second: '2-digit'
        });
        
        const timeEl = document.getElementById('current-time');
        if (timeEl) {
            timeEl.textContent = timeStr;
        }
    },

    showLoading(containerId) {
        const container = document.getElementById(containerId);
        if (container) {
            container.innerHTML = `
                <div style="display: flex; justify-content: center; align-items: center; height: 100%;">
                    <div class="loading"></div>
                    <span style="margin-left: 10px; color: #90a4ae;">加载中...</span>
                </div>
            `;
        }
    },

    showError(containerId, message) {
        const container = document.getElementById(containerId);
        if (container) {
            container.innerHTML = `
                <div style="text-align: center; padding: 40px; color: #f44336;">
                    <div style="font-size: 48px; margin-bottom: 16px;">⚠️</div>
                    <div>${message}</div>
                </div>
            `;
        }
    },

    showNoData(containerId, message = '暂无数据') {
        const container = document.getElementById(containerId);
        if (container) {
            container.innerHTML = `<div class="no-data">${message}</div>`;
        }
    },

    formatNumber(num, decimals = 2) {
        if (num === null || num === undefined || isNaN(num)) {
            return '--';
        }
        return Number(num).toFixed(decimals);
    },

    formatPercent(num, decimals = 1) {
        if (num === null || num === undefined || isNaN(num)) {
            return '--%';
        }
        return (Number(num) * 100).toFixed(decimals) + '%';
    },

    formatDate(date) {
        if (!date) return '--';
        const d = new Date(date);
        return d.toLocaleDateString('zh-CN');
    },

    formatDateTime(date) {
        if (!date) return '--';
        const d = new Date(date);
        return d.toLocaleString('zh-CN');
    },

    getConnectivityColor(coefficient) {
        if (coefficient >= 0.7) return '#00FF00';
        if (coefficient >= 0.4) return '#FFFF00';
        return '#FF0000';
    },

    getConnectivityLevel(coefficient) {
        if (coefficient >= 0.7) return { text: '强连通', class: 'high' };
        if (coefficient >= 0.4) return { text: '中等连通', class: 'medium' };
        return { text: '弱连通', class: 'low' };
    },

    getFaultTypeText(type) {
        const types = {
            'ROD_BREAK': '杆断',
            'PUMP_LEAK': '泵漏',
            'GAS_LOCK': '气锁',
            'VALVE_LEAK': '阀漏'
        };
        return types[type] || type;
    },

    getAlarmLevelText(level) {
        const levels = {
            'LEVEL_1': '一级告警',
            'LEVEL_2': '二级告警'
        };
        return levels[level] || level;
    },

    getEORScenarioText(scenario) {
        const scenarios = {
            'POLYMER_FLOODING': '聚合物驱',
            'SURFACTANT_FLOODING': '表面活性剂驱',
            'ASP_FLOODING': '三元复合驱',
            'CO2_FLOODING': 'CO₂驱',
            'STEAM_FLOODING': '蒸汽驱',
            'FIRE_FLOODING': '火驱'
        };
        return scenarios[scenario] || scenario;
    },

    createChartOption(title, xData, seriesData, type = 'line', color = '#4fc3f7') {
        return {
            title: {
                text: title,
                textStyle: {
                    color: '#4fc3f7',
                    fontSize: 14
                },
                left: 'center'
            },
            tooltip: {
                trigger: 'axis',
                backgroundColor: 'rgba(13, 33, 55, 0.95)',
                borderColor: '#2d5a87',
                textStyle: {
                    color: '#e0e6ed'
                }
            },
            grid: {
                left: '3%',
                right: '4%',
                bottom: '3%',
                containLabel: true
            },
            xAxis: {
                type: 'category',
                data: xData,
                axisLine: {
                    lineStyle: {
                        color: '#2d5a87'
                    }
                },
                axisLabel: {
                    color: '#90a4ae'
                }
            },
            yAxis: {
                type: 'value',
                axisLine: {
                    lineStyle: {
                        color: '#2d5a87'
                    }
                },
                axisLabel: {
                    color: '#90a4ae'
                },
                splitLine: {
                    lineStyle: {
                        color: '#1e3a5f'
                    }
                }
            },
            series: [{
                data: seriesData,
                type: type,
                smooth: true,
                lineStyle: {
                    color: color,
                    width: 2
                },
                itemStyle: {
                    color: color
                },
                areaStyle: type === 'line' ? {
                    color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
                        { offset: 0, color: color + '40' },
                        { offset: 1, color: color + '10' }
                    ])
                } : undefined
            }]
        };
    },

    createGaugeOption(title, value, max = 100, unit = '%') {
        return {
            series: [{
                type: 'gauge',
                startAngle: 180,
                endAngle: 0,
                min: 0,
                max: max,
                splitNumber: 5,
                radius: '100%',
                center: ['50%', '75%'],
                axisLine: {
                    lineStyle: {
                        width: 12,
                        color: [
                            [0.3, '#4caf50'],
                            [0.7, '#ff9800'],
                            [1, '#f44336']
                        ]
                    }
                },
                pointer: {
                    icon: 'path://M12.8,0.7l12,40.1H0.7L12.8,0.7z',
                    length: '60%',
                    width: 8,
                    itemStyle: {
                        color: '#4fc3f7'
                    }
                },
                axisTick: {
                    show: false
                },
                splitLine: {
                    length: 8,
                    lineStyle: {
                        color: '#2d5a87'
                    }
                },
                axisLabel: {
                    color: '#90a4ae',
                    fontSize: 10,
                    distance: -30
                },
                title: {
                    show: true,
                    offsetCenter: [0, '-20%'],
                    color: '#4fc3f7',
                    fontSize: 12
                },
                detail: {
                    fontSize: 20,
                    fontWeight: 'bold',
                    offsetCenter: [0, '0%'],
                    color: '#fff',
                    formatter: `{value}${unit}`
                },
                data: [{
                    value: value,
                    name: title
                }]
            }]
        };
    },

    createRadarOption(indicators, seriesData) {
        return {
            tooltip: {
                backgroundColor: 'rgba(13, 33, 55, 0.95)',
                borderColor: '#2d5a87',
                textStyle: {
                    color: '#e0e6ed'
                }
            },
            legend: {
                data: seriesData.map(s => s.name),
                textStyle: {
                    color: '#90a4ae'
                },
                bottom: 0
            },
            radar: {
                indicator: indicators,
                shape: 'circle',
                radius: '65%',
                center: ['50%', '50%'],
                splitNumber: 5,
                axisName: {
                    color: '#90a4ae',
                    fontSize: 12
                },
                splitLine: {
                    lineStyle: {
                        color: '#1e3a5f'
                    }
                },
                splitArea: {
                    areaStyle: {
                        color: ['rgba(30, 58, 95, 0.1)', 'rgba(30, 58, 95, 0.2)']
                    }
                },
                axisLine: {
                    lineStyle: {
                        color: '#2d5a87'
                    }
                }
            },
            series: [{
                type: 'radar',
                data: seriesData.map(s => ({
                    value: s.value,
                    name: s.name,
                    lineStyle: {
                        color: s.color,
                        width: 2
                    },
                    areaStyle: {
                        color: s.color + '30'
                    },
                    itemStyle: {
                        color: s.color
                    }
                }))
            }]
        };
    },

    createBarOption(title, categories, seriesData, colors) {
        return {
            title: {
                text: title,
                textStyle: {
                    color: '#4fc3f7',
                    fontSize: 14
                },
                left: 'center'
            },
            tooltip: {
                trigger: 'axis',
                backgroundColor: 'rgba(13, 33, 55, 0.95)',
                borderColor: '#2d5a87',
                textStyle: {
                    color: '#e0e6ed'
                }
            },
            grid: {
                left: '3%',
                right: '4%',
                bottom: '3%',
                containLabel: true
            },
            xAxis: {
                type: 'category',
                data: categories,
                axisLine: {
                    lineStyle: {
                        color: '#2d5a87'
                    }
                },
                axisLabel: {
                    color: '#90a4ae',
                    rotate: 30
                }
            },
            yAxis: {
                type: 'value',
                axisLine: {
                    lineStyle: {
                        color: '#2d5a87'
                    }
                },
                axisLabel: {
                    color: '#90a4ae'
                },
                splitLine: {
                    lineStyle: {
                        color: '#1e3a5f'
                    }
                }
            },
            series: seriesData.map((s, i) => ({
                name: s.name,
                type: 'bar',
                data: s.data,
                itemStyle: {
                    color: colors[i] || '#4fc3f7',
                    borderRadius: [4, 4, 0, 0]
                }
            }))
        };
    },

    async loadBlocks(selectorId) {
        try {
            const blocks = await this.fetchData('/wells/blocks');
            const selector = document.getElementById(selectorId);
            if (selector) {
                selector.innerHTML = '<option value="ALL">全部区块</option>';
                blocks.forEach(block => {
                    const option = document.createElement('option');
                    option.value = block;
                    option.textContent = block;
                    selector.appendChild(option);
                });
            }
            return blocks;
        } catch (error) {
            console.error('Failed to load blocks:', error);
            return [];
        }
    },

    getMockConnectivityData() {
        const nodes = [
            { id: 'W1', name: 'W1', category: 0, symbolSize: 30 },
            { id: 'W2', name: 'W2', category: 0, symbolSize: 28 },
            { id: 'W3', name: 'W3', category: 0, symbolSize: 32 },
            { id: 'P1', name: 'P1', category: 1, symbolSize: 26 },
            { id: 'P2', name: 'P2', category: 1, symbolSize: 24 },
            { id: 'P3', name: 'P3', category: 1, symbolSize: 28 },
            { id: 'P4', name: 'P4', category: 1, symbolSize: 22 }
        ];

        const links = [
            { source: 'W1', target: 'P1', value: 0.85, isPseudo: false },
            { source: 'W1', target: 'P2', value: 0.52, isPseudo: false },
            { source: 'W2', target: 'P2', value: 0.78, isPseudo: false },
            { source: 'W2', target: 'P3', value: 0.35, isPseudo: true },
            { source: 'W3', target: 'P3', value: 0.91, isPseudo: false },
            { source: 'W3', target: 'P4', value: 0.63, isPseudo: false },
            { source: 'W1', target: 'P4', value: 0.28, isPseudo: true }
        ];

        return { nodes, links };
    },

    getMockProfileData() {
        return {
            layers: ['层1', '层2', '层3', '层4', '层5', '层6'],
            currentInjection: [120, 95, 150, 80, 110, 70],
            targetInjection: [130, 110, 140, 95, 100, 85],
            smithData: {
                time: Array.from({ length: 30 }, (_, i) => `t${i + 1}`),
                traditional: Array.from({ length: 30 }, () => Math.random() * 20 + 80),
                compensated: Array.from({ length: 30 }, () => Math.random() * 10 + 90)
            },
            overshootStatus: {
                enabled: true,
                currentOvershoot: 2.3,
                threshold: 5.0
            },
            wellId: 'W1'
        };
    },

    getMockEORData() {
        return {
            indicators: [
                { name: '采收率', max: 100 },
                { name: '经济效益', max: 100 },
                { name: '技术可行性', max: 100 },
                { name: '环境影响', max: 100 },
                { name: '实施周期', max: 100 },
                { name: '风险等级', max: 100 }
            ],
            scenarios: [
                {
                    name: '聚合物驱',
                    value: [75, 68, 85, 70, 60, 55],
                    color: '#4fc3f7'
                },
                {
                    name: '表面活性剂驱',
                    value: [82, 72, 78, 65, 70, 60],
                    color: '#4caf50'
                },
                {
                    name: '三元复合驱',
                    value: [88, 78, 70, 55, 80, 65],
                    color: '#ff9800'
                },
                {
                    name: 'CO₂驱',
                    value: [70, 85, 65, 60, 75, 50],
                    color: '#9c27b0'
                }
            ],
            historyData: {
                months: ['1月', '2月', '3月', '4月', '5月', '6月'],
                actual: [45, 48, 52, 55, 58, 62],
                simulated: [44, 49, 51, 56, 57, 63]
            },
            optimizationProgress: [
                { name: '参数A', value: 85 },
                { name: '参数B', value: 62 },
                { name: '参数C', value: 93 },
                { name: '参数D', value: 45 },
                { name: '参数E', value: 78 }
            ],
            recommendations: [
                { rank: 1, name: '三元复合驱', recovery: 88, npv: 12500, period: '24个月', risk: '中' },
                { rank: 2, name: '表面活性剂驱', recovery: 82, npv: 10800, period: '18个月', risk: '低' },
                { rank: 3, name: '聚合物驱', recovery: 75, npv: 9200, period: '12个月', risk: '低' },
                { rank: 4, name: 'CO₂驱', recovery: 70, npv: 11500, period: '36个月', risk: '中高' }
            ]
        };
    },

    getMockFaultData() {
        return {
            faultProbabilities: {
                'ROD_BREAK': 0.15,
                'PUMP_LEAK': 0.42,
                'GAS_LOCK': 0.08,
                'VALVE_LEAK': 0.27
            },
            onnxPerformance: {
                totalInferenceCount: 15234,
                totalInferenceTimeMs: 45678,
                averageInferenceTimeMs: 3.0,
                executionProvider: 'CPU',
                numThreads: 4,
                modelLoaded: true
            },
            thresholds: {
                ROD_BREAK: 0.3,
                PUMP_LEAK: 0.25,
                GAS_LOCK: 0.4,
                VALVE_LEAK: 0.35
            },
            knowledgeSources: [
                { well: 'P1', source: '模型训练A', confidence: 0.92 },
                { well: 'P2', source: '迁移学习B', confidence: 0.85 },
                { well: 'P3', source: '领域知识', confidence: 0.78 }
            ],
            alarms: [
                { id: 1, level: 'LEVEL_2', type: 'PUMP_LEAK', well: 'P2', message: '抽油机P2泵漏风险升高', time: '2026-06-09 14:30:00' },
                { id: 2, level: 'LEVEL_1', type: 'VALVE_LEAK', well: 'P1', message: '抽油机P1阀漏概率超过阈值', time: '2026-06-09 13:15:00' },
                { id: 3, level: 'LEVEL_2', type: 'ROD_BREAK', well: 'P4', message: '抽油机P4电流异常下降', time: '2026-06-09 12:00:00' }
            ]
        };
    }
};

document.addEventListener('DOMContentLoaded', () => {
    ComponentUtils.updateTime();
    setInterval(() => ComponentUtils.updateTime(), 1000);
});
