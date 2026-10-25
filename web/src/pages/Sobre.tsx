export function Sobre() {
  return (
    <>
      <h1>Sobre os dados</h1>
      <h2>Dados sintéticos</h2>
      <p>
        Este painel é uma demonstração. As doses aplicadas são geradas por simulação e não
        representam pessoas reais. Nenhum dado individual é exibido.
      </p>
      <h2>Como a cobertura é calculada</h2>
      <p>
        O numerador é o número de doses sintéticas aplicadas. O denominador é a população do Censo
        2022 do IBGE para a faixa etária e o território correspondentes. Células com poucas doses
        aparecem como "&lt; 5" para evitar identificação.
      </p>
      <h2>Fontes e atribuição</h2>
      <p>
        Territórios, malhas e população: IBGE (Censo 2022 e Malhas Territoriais). Estabelecimentos
        de saúde: CNES, Ministério da Saúde. Os dados públicos seguem as licenças de cada fonte.
      </p>
    </>
  )
}
